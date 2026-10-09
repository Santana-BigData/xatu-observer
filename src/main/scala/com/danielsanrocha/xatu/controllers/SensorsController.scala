package com.danielsanrocha.xatu.controllers

import com.danielsanrocha.xatu.commons.{MetricsAggregator, SensorsAggregator}
import com.danielsanrocha.xatu.exceptions.BadArgumentException
import com.danielsanrocha.xatu.models.internals.RequestId
import com.danielsanrocha.xatu.models.requests.MetricsRequest
import com.danielsanrocha.xatu.models.responses.HitsResult
import com.danielsanrocha.xatu.repositories.SensorsRepository
import com.twitter.finagle.context.Contexts
import com.twitter.finatra.http.Controller
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

class SensorsController(intervalSeconds: Long)(implicit val repository: SensorsRepository, implicit val ec: scala.concurrent.ExecutionContext) extends Controller {
  private val logging: Logger = Logger(this.getClass)

  private val maxRangeMillis = 8L * 24 * 3600 * 1000
  private val defaultRangeMillis = 3600L * 1000

  /** Cpu temperatures, fans and ambient temperature of a server, with min/avg/max per step. */
  get("/api/metrics/sensors") { request: MetricsRequest =>
    val requestId = Contexts.local.get(RequestId).head.requestId
    logging.info(s"(x-request-id - $requestId) Sensors route called for server ${request.server}...")

    val to = request.to.getOrElse(System.currentTimeMillis())
    val from = request.from.getOrElse(to - defaultRangeMillis)

    val validation =
      if (request.server.trim.isEmpty) Some("server is required")
      else if (from > to) Some("from must be before to")
      else if (to - from > maxRangeMillis) Some("range must be at most 8 days")
      else if (request.step.exists(_ < 1)) Some("step must be positive")
      else None

    validation match {
      case Some(message) => Future.failed(new BadArgumentException(message))
      case None =>
        val step = request.step.getOrElse(MetricsAggregator.defaultStep(from, to, intervalSeconds))
        repository.byServer(request.server, from, to) map { readings =>
          val points = SensorsAggregator.aggregate(readings, step)
          HitsResult(points.length, points)
        }
    }
  }
}
