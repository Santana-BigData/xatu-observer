package com.danielsanrocha.xatu.repositories

import com.danielsanrocha.xatu.models.internals.{ContainerLocation, ContainerMetrics}
import com.datastax.oss.driver.api.core.cql.{AsyncResultSet, PreparedStatement, Row}

import java.time.{Instant, LocalDate, ZoneOffset}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

/** Each sample goes to the table by name, the table by server and the inventory. */
class ContainerMetricsRepositoryImpl(cassandra: CassandraSession)(implicit val ec: ExecutionContext) extends ContainerMetricsRepository {

  private val columns =
    """container_id, image, started_at, restart_count, oom_killed, health, cpu_percent, cpu_limit_cores,
      |mem_usage_bytes, mem_limit_bytes, net_rx_bytes_per_sec, net_tx_bytes_per_sec, block_read_bytes_per_sec,
      |block_write_bytes_per_sec, pids""".stripMargin

  private lazy val psInsertByName = cassandra.prepare(
    s"INSERT INTO container_metrics_by_name (container_name, day, collected_at, server, $columns) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
  )
  private lazy val psInsertByServer = cassandra.prepare(
    s"INSERT INTO container_metrics_by_server (server, day, collected_at, container_name, $columns) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
  )
  private lazy val psInsertInventory = cassandra.prepare(
    "INSERT INTO container_inventory (container_name, server, container_id, image, started_at, last_seen) VALUES (?,?,?,?,?,?)"
  )
  private lazy val psInventory = cassandra.prepare("SELECT * FROM container_inventory")
  // collected_at is a clustering column, so the range is resolved inside the partition
  private lazy val psByName = cassandra.prepare(
    "SELECT * FROM container_metrics_by_name WHERE container_name = ? AND day = ? AND collected_at >= ? AND collected_at <= ?"
  )
  private lazy val psByServer = cassandra.prepare(
    "SELECT * FROM container_metrics_by_server WHERE server = ? AND day = ? AND collected_at >= ? AND collected_at <= ?"
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

  private def values(m: ContainerMetrics): Seq[AnyRef] =
    Seq(
      m.containerId,
      m.image,
      m.startedAt.map(Instant.ofEpochMilli).orNull,
      Int.box(m.restartCount),
      Boolean.box(m.oomKilled),
      m.health.orNull,
      Double.box(m.cpuPercent),
      Double.box(m.cpuLimitCores),
      Long.box(m.memUsageBytes),
      Long.box(m.memLimitBytes),
      Double.box(m.netRxBytesPerSec),
      Double.box(m.netTxBytesPerSec),
      Double.box(m.blockReadBytesPerSec),
      Double.box(m.blockWriteBytesPerSec),
      Int.box(m.pids)
    )

  override def save(samples: Seq[ContainerMetrics]): Future[Unit] =
    Future
      .sequence(samples.flatMap { m =>
        val at = Instant.ofEpochMilli(m.collectedAt)
        val day = at.atZone(ZoneOffset.UTC).toLocalDate
        Seq(
          run(psInsertByName)(Seq(m.containerName, day, at, m.server) ++ values(m): _*),
          run(psInsertByServer)(Seq(m.server, day, at, m.containerName) ++ values(m): _*),
          run(psInsertInventory)(m.containerName, m.server, m.containerId, m.image, m.startedAt.map(Instant.ofEpochMilli).orNull, at)
        )
      })
      .map(_ => ())

  override def inventory(): Future[Seq[ContainerLocation]] =
    run(psInventory)().flatMap(all).map(_.map { r =>
      ContainerLocation(
        r.getString("container_name"),
        r.getString("server"),
        r.getString("container_id"),
        r.getString("image"),
        Option(r.getInstant("started_at")).map(_.toEpochMilli),
        r.getInstant("last_seen").toEpochMilli
      )
    }.sortBy(_.containerName))

  private def toMetrics(r: Row): ContainerMetrics =
    ContainerMetrics(
      server = r.getString("server"),
      containerName = r.getString("container_name"),
      containerId = r.getString("container_id"),
      image = r.getString("image"),
      collectedAt = r.getInstant("collected_at").toEpochMilli,
      startedAt = Option(r.getInstant("started_at")).map(_.toEpochMilli),
      restartCount = r.getInt("restart_count"),
      oomKilled = r.getBoolean("oom_killed"),
      health = Option(r.getString("health")),
      cpuPercent = r.getDouble("cpu_percent"),
      cpuLimitCores = r.getDouble("cpu_limit_cores"),
      memUsageBytes = r.getLong("mem_usage_bytes"),
      memLimitBytes = r.getLong("mem_limit_bytes"),
      netRxBytesPerSec = r.getDouble("net_rx_bytes_per_sec"),
      netTxBytesPerSec = r.getDouble("net_tx_bytes_per_sec"),
      blockReadBytesPerSec = r.getDouble("block_read_bytes_per_sec"),
      blockWriteBytesPerSec = r.getDouble("block_write_bytes_per_sec"),
      pids = r.getInt("pids")
    )

  /** One query per day partition between from and to. */
  private def query(ps: => Option[PreparedStatement], key: String, from: Long, to: Long): Future[Seq[ContainerMetrics]] = {
    val start = Instant.ofEpochMilli(from)
    val end = Instant.ofEpochMilli(to)
    val lastDay = end.atZone(ZoneOffset.UTC).toLocalDate
    val days = Iterator.iterate(start.atZone(ZoneOffset.UTC).toLocalDate)(_.plusDays(1)).takeWhile(!_.isAfter(lastDay)).toSeq
    Future
      .sequence(days.map((day: LocalDate) => run(ps)(key, day, start, end).flatMap(all)))
      .map(_.flatten.map(toMetrics).sortBy(m => (m.collectedAt, m.containerName, m.server)))
  }

  override def byName(name: String, from: Long, to: Long): Future[Seq[ContainerMetrics]] = query(psByName, name, from, to)

  override def byServer(server: String, from: Long, to: Long): Future[Seq[ContainerMetrics]] = query(psByServer, server, from, to)
}
