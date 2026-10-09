package com.danielsanrocha.xatu

import com.danielsanrocha.xatu.commons.HardwareSensorsReader
import com.danielsanrocha.xatu.repositories.SensorsRepository
import com.typesafe.scalalogging.Logger

import scala.concurrent.ExecutionContext

/** Samples the cpu temperatures (and fans with ipmi) of this server and saves them. Failures are only logged. */
class SensorsCollector(reader: HardwareSensorsReader, repository: SensorsRepository, intervalSeconds: Long)(implicit val ec: ExecutionContext)
    extends Periodic("SensorsCollector", intervalSeconds) {
  private val logging: Logger = Logger(this.getClass)

  override protected def run(): Unit = {
    val readings = reader.sample()
    logging.debug(s"${readings.size} sensor readings")
    if (readings.nonEmpty)
      repository.save(readings) recover { case e: Throwable =>
        logging.error(s"Error saving sensors on cassandra. Message: ${e.getMessage}")
      }
  }
}
