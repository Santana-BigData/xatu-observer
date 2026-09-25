package com.danielsanrocha.xatu.repositories

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.config.{DefaultDriverOption, DriverConfigLoader}
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger

import java.net.InetSocketAddress
import java.util.concurrent.CompletionStage
import java.util.function.BiFunction
import scala.concurrent.{Future, Promise}
import scala.util.Try

/**
 * Single connection to cassandra. If the cluster is down when Xatu starts,
 * `session` is empty and the repositories degrade instead of stopping the app.
 */
class CassandraSession(
    contactPoints: Seq[String],
    port: Int,
    keyspace: Option[String],
    datacenter: String,
    timeoutMs: Long,
    credentials: Option[(String, String)]
) {
  private val logging: Logger = Logger(this.getClass)

  val session: Option[CqlSession] =
    Try {
      val loader = DriverConfigLoader
        .programmaticBuilder()
        .withLong(DefaultDriverOption.REQUEST_TIMEOUT, timeoutMs)
        .withLong(DefaultDriverOption.CONNECTION_INIT_QUERY_TIMEOUT, timeoutMs)
        .withLong(DefaultDriverOption.CONTROL_CONNECTION_TIMEOUT, timeoutMs)
        // schema changes (migrations) wait for all nodes to agree
        .withLong(DefaultDriverOption.CONTROL_CONNECTION_AGREEMENT_TIMEOUT, 30000)
        .build()
      val builder = CqlSession
        .builder()
        .withConfigLoader(loader)
        .withLocalDatacenter(datacenter)
      keyspace.foreach(k => builder.withKeyspace(k))
      credentials.foreach { case (user, password) => builder.withAuthCredentials(user, password) }
      contactPoints.foreach(h => builder.addContactPoint(new InetSocketAddress(h, port)))
      builder.build()
    }.fold(
      e => { logging.error(s"Cassandra unavailable: ${e.getMessage}"); None },
      s => { logging.info(s"Cassandra connected (keyspace ${keyspace.getOrElse("-")})"); Some(s) }
    )

  def prepare(cql: String): Option[PreparedStatement] =
    session.flatMap { s =>
      Try(s.prepare(cql)).fold(
        e => { logging.error(s"Error preparing cql `$cql`: ${e.getMessage}"); None },
        ps => Some(ps)
      )
    }

  def close(): Unit = session.foreach(s => Try(s.close()))
}

object CassandraSession {

  /** Builds the session from the `cassandra` block of application.conf. */
  def fromConfig(conf: Config, withKeyspace: Boolean = true): CassandraSession = {
    def optionalString(path: String): Option[String] =
      if (conf.hasPath(path)) Some(conf.getString(path)).filter(_.nonEmpty) else None

    new CassandraSession(
      conf.getString("cassandra.contact_points").split(",").map(_.trim).filter(_.nonEmpty).toSeq,
      conf.getInt("cassandra.port"),
      if (withKeyspace) Some(conf.getString("cassandra.keyspace")) else None,
      conf.getString("cassandra.datacenter"),
      conf.getLong("cassandra.timeout_ms"),
      for {
        user <- optionalString("cassandra.user")
        password <- optionalString("cassandra.password")
      } yield (user, password)
    )
  }

  /** CompletionStage (java driver) -> scala Future. */
  def asScala[A](cs: CompletionStage[A]): Future[A] = {
    val p = Promise[A]()
    cs.handle[Unit](new BiFunction[A, Throwable, Unit] {
      override def apply(value: A, error: Throwable): Unit =
        if (error != null) p.failure(error) else p.success(value)
    })
    p.future
  }
}
