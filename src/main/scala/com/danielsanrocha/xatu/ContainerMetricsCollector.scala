package com.danielsanrocha.xatu

import com.danielsanrocha.xatu.commons.ContainerStatsReader
import com.danielsanrocha.xatu.repositories.ContainerMetricsRepository
import com.typesafe.scalalogging.Logger

import scala.concurrent.ExecutionContext

/** Samples every running docker container of this server and saves it. Failures are only logged. */
class ContainerMetricsCollector(reader: ContainerStatsReader, repository: ContainerMetricsRepository, intervalSeconds: Long)(implicit val ec: ExecutionContext)
    extends Periodic("ContainerMetricsCollector", intervalSeconds) {
  private val logging: Logger = Logger(this.getClass)

  override protected def run(): Unit = {
    val samples = reader.sample()
    if (samples.nonEmpty)
      repository.save(samples) recover { case e: Throwable =>
        logging.error(s"Error saving container metrics on cassandra. Message: ${e.getMessage}")
      }
  }
}
