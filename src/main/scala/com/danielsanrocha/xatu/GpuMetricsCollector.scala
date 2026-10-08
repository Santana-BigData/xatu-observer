package com.danielsanrocha.xatu

import com.danielsanrocha.xatu.commons.NvidiaSmiReader
import com.danielsanrocha.xatu.repositories.GpuMetricsRepository
import com.typesafe.scalalogging.Logger

import scala.concurrent.ExecutionContext

/** Samples every NVIDIA GPU of this server with nvidia-smi and saves it. Failures are only logged. */
class GpuMetricsCollector(reader: NvidiaSmiReader, repository: GpuMetricsRepository, intervalSeconds: Long)(implicit val ec: ExecutionContext)
    extends Periodic("GpuMetricsCollector", intervalSeconds) {
  private val logging: Logger = Logger(this.getClass)

  override protected def run(): Unit = {
    val samples = reader.sample()
    logging.debug(s"${samples.size} gpu samples")
    if (samples.nonEmpty)
      repository.save(samples) recover { case e: Throwable =>
        logging.error(s"Error saving gpu metrics on cassandra. Message: ${e.getMessage}")
      }
  }
}
