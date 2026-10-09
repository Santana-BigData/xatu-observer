package com.danielsanrocha.xatu.repositories

import com.danielsanrocha.xatu.models.internals.SensorReading
import com.datastax.oss.driver.api.core.cql.{AsyncResultSet, PreparedStatement, Row}

import java.time.{Instant, LocalDate, ZoneOffset}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

/** Hardware sensors, schema in src/main/resources/cassandra/V004__hardware_sensors.cql. */
trait SensorsRepository {
  def save(readings: Seq[SensorReading]): Future[Unit]

  /** Readings of every sensor of a server, oldest first. */
  def byServer(server: String, from: Long, to: Long): Future[Seq[SensorReading]]
}

class SensorsRepositoryDummyImpl extends SensorsRepository {
  override def save(readings: Seq[SensorReading]): Future[Unit] = Future.unit
  override def byServer(server: String, from: Long, to: Long): Future[Seq[SensorReading]] = Future.successful(Seq())
}

class SensorsRepositoryImpl(cassandra: CassandraSession)(implicit val ec: ExecutionContext) extends SensorsRepository {

  private lazy val psInsert = cassandra.prepare(
    "INSERT INTO sensors_by_server (server, day, collected_at, sensor, kind, value, high, crit) VALUES (?,?,?,?,?,?,?,?)"
  )
  private lazy val psByServer = cassandra.prepare(
    "SELECT * FROM sensors_by_server WHERE server = ? AND day = ? AND collected_at >= ? AND collected_at <= ?"
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

  override def save(readings: Seq[SensorReading]): Future[Unit] =
    Future
      .sequence(readings.map { r =>
        val at = Instant.ofEpochMilli(r.collectedAt)
        run(psInsert)(
          r.server, at.atZone(ZoneOffset.UTC).toLocalDate, at, r.sensor, r.kind, Double.box(r.value),
          r.high.map(Double.box).orNull, r.crit.map(Double.box).orNull
        )
      })
      .map(_ => ())

  private def optDouble(r: Row, column: String): Option[Double] = if (r.isNull(column)) None else Some(r.getDouble(column))

  override def byServer(server: String, from: Long, to: Long): Future[Seq[SensorReading]] = {
    val start = Instant.ofEpochMilli(from)
    val end = Instant.ofEpochMilli(to)
    val lastDay = end.atZone(ZoneOffset.UTC).toLocalDate
    val days = Iterator.iterate(start.atZone(ZoneOffset.UTC).toLocalDate)(_.plusDays(1)).takeWhile(!_.isAfter(lastDay)).toSeq
    Future
      .sequence(days.map((day: LocalDate) => run(psByServer)(server, day, start, end).flatMap(all)))
      .map(_.flatten.map { r =>
        SensorReading(r.getString("server"), r.getInstant("collected_at").toEpochMilli, r.getString("sensor"), r.getString("kind"), r.getDouble("value"), optDouble(r, "high"), optDouble(r, "crit"))
      }.sortBy(r => (r.collectedAt, r.sensor)))
  }
}
