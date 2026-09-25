package com.danielsanrocha.xatu

import com.danielsanrocha.xatu.commons.SystemMetricsReader
import com.danielsanrocha.xatu.repositories.MetricsRepository
import com.typesafe.scalalogging.Logger

import java.util.concurrent.{ScheduledFuture, ScheduledThreadPoolExecutor, TimeUnit}
import scala.concurrent.ExecutionContext

/** Samples the host every `intervalSeconds` and saves it. Failures are only logged. */
class MetricsCollector(reader: SystemMetricsReader, repository: MetricsRepository, intervalSeconds: Int)(implicit val ec: ExecutionContext) {
  private val logging: Logger = Logger(this.getClass)

  private val ex = new ScheduledThreadPoolExecutor(1)

  val task: Runnable = () => {
    try {
      reader.sample().foreach { metrics =>
        repository.save(metrics) recover { case e: Throwable =>
          logging.error(s"Error saving metrics on cassandra. Message: ${e.getMessage}")
        }
      }
    } catch {
      case e: Throwable => logging.error(s"Error reading server metrics. Message: ${e.getMessage}")
    }
  }

  var interval: Option[ScheduledFuture[_]] = None

  def start(): Unit = {
    logging.info(s"Starting MetricsCollector, one sample every $intervalSeconds seconds...")
    // first run only records the cpu/network counters, the first sample is saved one interval later
    interval = Some(ex.scheduleAtFixedRate(task, 0, intervalSeconds, TimeUnit.SECONDS))
  }
}
