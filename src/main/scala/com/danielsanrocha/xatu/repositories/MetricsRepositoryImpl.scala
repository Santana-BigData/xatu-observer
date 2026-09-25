package com.danielsanrocha.xatu.repositories

import com.danielsanrocha.xatu.models.internals.{MetricsServer, ServerMetrics}
import com.datastax.oss.driver.api.core.cql.{AsyncResultSet, PreparedStatement, Row}
import com.typesafe.scalalogging.Logger

import java.time.{Instant, LocalDate, ZoneOffset}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

/**
 * Metrics in cassandra, schema in src/main/resources/cassandra/V001__metrics.cql.
 * Samples are partitioned by (server, day), so a query reads one partition per day.
 */
class MetricsRepositoryImpl(cassandra: CassandraSession)(implicit val ec: ExecutionContext) extends MetricsRepository {
  private val logging: Logger = Logger(this.getClass)

  override val active: Boolean = true

  private def unavailable = Future.failed(new Exception("Cassandra unavailable"))

  private lazy val psInsert = cassandra.prepare(
    """INSERT INTO metrics_by_server (server, day, collected_at, cpu_percent, cpu_cores, load_1m, load_5m,
      |  load_15m, mem_total_bytes, mem_used_bytes, mem_available_bytes, swap_total_bytes, swap_used_bytes,
      |  disk_total_bytes, disk_used_bytes, net_rx_bytes_per_sec, net_tx_bytes_per_sec)
      |VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""".stripMargin
  )

  private lazy val psInsertServer = cassandra.prepare(
    "INSERT INTO metrics_servers (server, last_seen, cpu_cores, mem_total_bytes) VALUES (?,?,?,?)"
  )

  private lazy val psServers = cassandra.prepare("SELECT * FROM metrics_servers")

  // collected_at is the clustering key, so the range is resolved inside the partition
  private lazy val psQuery = cassandra.prepare(
    """SELECT * FROM metrics_by_server
      |WHERE server = ? AND day = ? AND collected_at >= ? AND collected_at <= ?""".stripMargin
  )

  private lazy val psStatus = cassandra.prepare("SELECT release_version FROM system.local")

  private def run(ps: Option[PreparedStatement])(args: AnyRef*): Future[AsyncResultSet] =
    (cassandra.session, ps) match {
      case (Some(s), Some(p)) => CassandraSession.asScala(s.executeAsync(p.bind(args: _*)))
      case _                  => unavailable
    }

  /** Reads every page of the result. */
  private def all(rs: AsyncResultSet): Future[Seq[Row]] = {
    val rows = rs.currentPage().asScala.toSeq
    if (rs.hasMorePages) CassandraSession.asScala(rs.fetchNextPage()).flatMap(all).map(rows ++ _)
    else Future.successful(rows)
  }

  override def save(m: ServerMetrics): Future[Unit] = {
    val at = Instant.ofEpochMilli(m.collectedAt)
    val day = at.atZone(ZoneOffset.UTC).toLocalDate

    val sample = run(psInsert)(
      m.server,
      day,
      at,
      Double.box(m.cpuPercent),
      Int.box(m.cpuCores),
      Double.box(m.load1m),
      Double.box(m.load5m),
      Double.box(m.load15m),
      Long.box(m.memTotalBytes),
      Long.box(m.memUsedBytes),
      Long.box(m.memAvailableBytes),
      Long.box(m.swapTotalBytes),
      Long.box(m.swapUsedBytes),
      Long.box(m.diskTotalBytes),
      Long.box(m.diskUsedBytes),
      Double.box(m.netRxBytesPerSec),
      Double.box(m.netTxBytesPerSec)
    )
    val server = run(psInsertServer)(m.server, at, Int.box(m.cpuCores), Long.box(m.memTotalBytes))

    Future.sequence(Seq(sample, server)).map(_ => ())
  }

  override def servers(): Future[Seq[MetricsServer]] =
    run(psServers)().flatMap(all).map { rows =>
      rows.map { r =>
        MetricsServer(r.getString("server"), r.getInstant("last_seen").toEpochMilli, r.getInt("cpu_cores"), r.getLong("mem_total_bytes"))
      }.sortBy(_.server)
    }

  private def toMetrics(r: Row): ServerMetrics =
    ServerMetrics(
      server = r.getString("server"),
      collectedAt = r.getInstant("collected_at").toEpochMilli,
      cpuPercent = r.getDouble("cpu_percent"),
      cpuCores = r.getInt("cpu_cores"),
      load1m = r.getDouble("load_1m"),
      load5m = r.getDouble("load_5m"),
      load15m = r.getDouble("load_15m"),
      memTotalBytes = r.getLong("mem_total_bytes"),
      memUsedBytes = r.getLong("mem_used_bytes"),
      memAvailableBytes = r.getLong("mem_available_bytes"),
      swapTotalBytes = r.getLong("swap_total_bytes"),
      swapUsedBytes = r.getLong("swap_used_bytes"),
      diskTotalBytes = r.getLong("disk_total_bytes"),
      diskUsedBytes = r.getLong("disk_used_bytes"),
      netRxBytesPerSec = r.getDouble("net_rx_bytes_per_sec"),
      netTxBytesPerSec = r.getDouble("net_tx_bytes_per_sec")
    )

  override def query(server: String, from: Long, to: Long): Future[Seq[ServerMetrics]] = {
    val start = Instant.ofEpochMilli(from)
    val end = Instant.ofEpochMilli(to)
    val firstDay = start.atZone(ZoneOffset.UTC).toLocalDate
    val lastDay = end.atZone(ZoneOffset.UTC).toLocalDate
    val days = Iterator.iterate(firstDay)(_.plusDays(1)).takeWhile(!_.isAfter(lastDay)).toSeq

    logging.debug(s"Querying metrics of $server in ${days.size} day partitions")

    Future
      .sequence(days.map((day: LocalDate) => run(psQuery)(server, day, start, end).flatMap(all)))
      .map(_.flatten.map(toMetrics).sortBy(_.collectedAt))
  }

  override def status(): Future[Unit] = run(psStatus)().map(_ => ())

  override def close(): Unit = cassandra.close()
}
