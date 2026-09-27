package com.danielsanrocha.xatu.repositories

import com.danielsanrocha.xatu.models.internals.{ContainerLocation, ContainerMetrics}

import scala.concurrent.Future

/** Docker container metrics, schema in src/main/resources/cassandra/V002__container_metrics.cql. */
trait ContainerMetricsRepository {
  def save(samples: Seq[ContainerMetrics]): Future[Unit]

  /** Where each container seen in the last 7 days is (or was last) running. */
  def inventory(): Future[Seq[ContainerLocation]]

  /** Samples of a container by name, on any server, oldest first. */
  def byName(name: String, from: Long, to: Long): Future[Seq[ContainerMetrics]]

  /** Samples of every container of a server, oldest first. */
  def byServer(server: String, from: Long, to: Long): Future[Seq[ContainerMetrics]]
}

class ContainerMetricsRepositoryDummyImpl extends ContainerMetricsRepository {
  override def save(samples: Seq[ContainerMetrics]): Future[Unit] = Future.unit
  override def inventory(): Future[Seq[ContainerLocation]] = Future.successful(Seq())
  override def byName(name: String, from: Long, to: Long): Future[Seq[ContainerMetrics]] = Future.successful(Seq())
  override def byServer(server: String, from: Long, to: Long): Future[Seq[ContainerMetrics]] = Future.successful(Seq())
}
