package com.danielsanrocha.xatu.repositories

import com.danielsanrocha.xatu.models.internals.{GpuInfo, GpuMetrics, GpuProcess}
import com.datastax.oss.driver.api.core.cql.{AsyncResultSet, PreparedStatement, Row}

import java.time.{Instant, LocalDate, ZoneOffset}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

/** GPU metrics, schema in src/main/resources/cassandra/V003__gpu_metrics.cql. */
trait GpuMetricsRepository {
  def save(samples: Seq[GpuMetrics]): Future[Unit]

  /** GPUs seen in the last 7 days. */
  def inventory(): Future[Seq[GpuInfo]]

  /** Samples of every GPU of a server, oldest first. */
  def byServer(server: String, from: Long, to: Long): Future[Seq[GpuMetrics]]
}

class GpuMetricsRepositoryDummyImpl extends GpuMetricsRepository {
  override def save(samples: Seq[GpuMetrics]): Future[Unit] = Future.unit
  override def inventory(): Future[Seq[GpuInfo]] = Future.successful(Seq())
  override def byServer(server: String, from: Long, to: Long): Future[Seq[GpuMetrics]] = Future.successful(Seq())
}

class GpuMetricsRepositoryImpl(cassandra: CassandraSession)(implicit val ec: ExecutionContext) extends GpuMetricsRepository {

  private lazy val psInsert = cassandra.prepare(
    """INSERT INTO gpu_metrics_by_server (server, day, collected_at, gpu_index, uuid, name, driver_version,
      |  utilization_gpu_percent, utilization_mem_percent, mem_used_bytes, mem_total_bytes, temperature_c,
      |  power_draw_w, power_limit_w, fan_percent, sm_clock_mhz, mem_clock_mhz, pstate, throttle_reasons, processes)
      |VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""".stripMargin
  )
  private lazy val psInsertInventory = cassandra.prepare(
    "INSERT INTO gpu_inventory (server, gpu_index, uuid, name, driver_version, mem_total_bytes, last_seen) VALUES (?,?,?,?,?,?,?)"
  )
  private lazy val psInventory = cassandra.prepare("SELECT * FROM gpu_inventory")
  private lazy val psByServer = cassandra.prepare(
    "SELECT * FROM gpu_metrics_by_server WHERE server = ? AND day = ? AND collected_at >= ? AND collected_at <= ?"
  )

  private def run(ps: Option[PreparedStatement])(args: AnyRef*): Future[AsyncResultSet] =
    (cassandra.session, ps) match {
      case (Some(s), Some(p)) => CassandraSession.asScala(s.executeAsync(p.bind(args: _*)))
      case _                  => Future.failed(new Exception("Cassandra unavailable"))
    }

  private def all(rs: AsyncResultSet): Future[Seq[Row]] = {
    val rows = rs.currentPage().asScala.toSeq
    if (rs.hasMorePages) CassandraSession.asScala(rs.fetchNextPage()).flatMap(all).map(rows ++ _)
    else Future.successful(rows)
  }

  private def processesJson(ps: Seq[GpuProcess]): String =
    ujson.Arr.from(ps.map(p => ujson.Obj("pid" -> ujson.Num(p.pid.toDouble), "name" -> p.name, "mem_used_bytes" -> ujson.Num(p.memUsedBytes.toDouble)))).render()

  private def processesFromJson(json: String): Seq[GpuProcess] =
    Option(json).filter(_.nonEmpty).map(ujson.read(_).arr.toSeq.map(o => GpuProcess(o("pid").num.toLong, o("name").str, o("mem_used_bytes").num.toLong))).getOrElse(Seq())

  override def save(samples: Seq[GpuMetrics]): Future[Unit] =
    Future
      .sequence(samples.flatMap { m =>
        val at = Instant.ofEpochMilli(m.collectedAt)
        val day = at.atZone(ZoneOffset.UTC).toLocalDate
        Seq(
          run(psInsert)(
            m.server, day, at, Int.box(m.gpuIndex), m.uuid, m.name, m.driverVersion,
            Int.box(m.utilizationGpuPercent), Int.box(m.utilizationMemPercent), Long.box(m.memUsedBytes), Long.box(m.memTotalBytes),
            Int.box(m.temperatureC), Double.box(m.powerDrawW), Double.box(m.powerLimitW), m.fanPercent.map(Int.box).orNull,
            Int.box(m.smClockMhz), Int.box(m.memClockMhz), m.pstate, m.throttleReasons.asJava, processesJson(m.processes)
          ),
          run(psInsertInventory)(m.server, Int.box(m.gpuIndex), m.uuid, m.name, m.driverVersion, Long.box(m.memTotalBytes), at)
        )
      })
      .map(_ => ())

  override def inventory(): Future[Seq[GpuInfo]] =
    run(psInventory)().flatMap(all).map(_.map { r =>
      GpuInfo(r.getString("server"), r.getInt("gpu_index"), r.getString("uuid"), r.getString("name"), r.getString("driver_version"), r.getLong("mem_total_bytes"), r.getInstant("last_seen").toEpochMilli)
    }.sortBy(g => (g.server, g.gpuIndex)))

  private def toMetrics(r: Row): GpuMetrics =
    GpuMetrics(
      server = r.getString("server"),
      collectedAt = r.getInstant("collected_at").toEpochMilli,
      gpuIndex = r.getInt("gpu_index"),
      uuid = r.getString("uuid"),
      name = r.getString("name"),
      driverVersion = r.getString("driver_version"),
      utilizationGpuPercent = r.getInt("utilization_gpu_percent"),
      utilizationMemPercent = r.getInt("utilization_mem_percent"),
      memUsedBytes = r.getLong("mem_used_bytes"),
      memTotalBytes = r.getLong("mem_total_bytes"),
      temperatureC = r.getInt("temperature_c"),
      powerDrawW = r.getDouble("power_draw_w"),
      powerLimitW = r.getDouble("power_limit_w"),
      fanPercent = if (r.isNull("fan_percent")) None else Some(r.getInt("fan_percent")),
      smClockMhz = r.getInt("sm_clock_mhz"),
      memClockMhz = r.getInt("mem_clock_mhz"),
      pstate = r.getString("pstate"),
      throttleReasons = Option(r.getList("throttle_reasons", classOf[String])).map(_.asScala.toSeq).getOrElse(Seq()),
      processes = processesFromJson(r.getString("processes"))
    )

  override def byServer(server: String, from: Long, to: Long): Future[Seq[GpuMetrics]] = {
    val start = Instant.ofEpochMilli(from)
    val end = Instant.ofEpochMilli(to)
    val lastDay = end.atZone(ZoneOffset.UTC).toLocalDate
    val days = Iterator.iterate(start.atZone(ZoneOffset.UTC).toLocalDate)(_.plusDays(1)).takeWhile(!_.isAfter(lastDay)).toSeq
    Future
      .sequence(days.map((day: LocalDate) => run(psByServer)(server, day, start, end).flatMap(all)))
      .map(_.flatten.map(toMetrics).sortBy(m => (m.collectedAt, m.gpuIndex)))
  }
}
