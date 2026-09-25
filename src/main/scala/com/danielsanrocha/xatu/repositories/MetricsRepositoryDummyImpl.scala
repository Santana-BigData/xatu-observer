package com.danielsanrocha.xatu.repositories

import com.danielsanrocha.xatu.models.internals.{MetricsServer, ServerMetrics}

import scala.concurrent.Future

/** Used when cassandra is disabled: nothing is saved and every query is empty. */
class MetricsRepositoryDummyImpl extends MetricsRepository {
  override val active: Boolean = false

  override def save(metrics: ServerMetrics): Future[Unit] = Future.unit

  override def servers(): Future[Seq[MetricsServer]] = Future.successful(Seq())

  override def query(server: String, from: Long, to: Long): Future[Seq[ServerMetrics]] = Future.successful(Seq())

  override def status(): Future[Unit] = Future.unit

  override def close(): Unit = ()
}
