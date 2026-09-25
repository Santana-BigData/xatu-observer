package com.danielsanrocha.xatu.repositories

import com.danielsanrocha.xatu.models.internals.{MetricsServer, ServerMetrics}

import scala.concurrent.Future

trait MetricsRepository {

  /** False when cassandra is disabled (CASSANDRA_ACTIVE=false). */
  def active: Boolean

  def save(metrics: ServerMetrics): Future[Unit]

  /** Servers that sent metrics in the last 7 days. */
  def servers(): Future[Seq[MetricsServer]]

  /** Samples of `server` between `from` and `to` (epoch millis, inclusive), oldest first. */
  def query(server: String, from: Long, to: Long): Future[Seq[ServerMetrics]]

  def status(): Future[Unit]

  def close(): Unit
}
