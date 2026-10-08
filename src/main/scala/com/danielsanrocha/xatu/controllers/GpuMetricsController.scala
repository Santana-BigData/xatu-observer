package com.danielsanrocha.xatu.controllers

import com.danielsanrocha.xatu.commons.{GpuMetricsAggregator, MetricsAggregator}
import com.danielsanrocha.xatu.exceptions.BadArgumentException
import com.danielsanrocha.xatu.models.internals.RequestId
import com.danielsanrocha.xatu.models.requests.MetricsRequest
import com.danielsanrocha.xatu.models.responses.HitsResult
import com.danielsanrocha.xatu.repositories.GpuMetricsRepository
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.http.Request
import com.twitter.finatra.http.Controller
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

class GpuMetricsController(intervalSeconds: Long)(implicit val repository: GpuMetricsRepository, implicit val ec: scala.concurrent.ExecutionContext) extends Controller {
  private val logging: Logger = Logger(this.getClass)

  private val maxRangeMillis = 8L * 24 * 3600 * 1000
  private val defaultRangeMillis = 3600L * 1000

  /** GPUs of every server seen in the last 7 days. */
  get("/api/metrics/gpus") { _: Request =>
    val requestId = Contexts.local.get(RequestId).head.requestId
    logging.info(s"(x-request-id - $requestId) GPU inventory route called...")
    repository.inventory() map { gpus => HitsResult(gpus.length, gpus) }
  }

  /** Samples of every GPU of a server, averaged by step (default: ~720 points per line). */
  get("/api/metrics/gpus/samples") { request: MetricsRequest =>
    val requestId = Contexts.local.get(RequestId).head.requestId
    logging.info(s"(x-request-id - $requestId) GPU samples route called for server ${request.server}...")

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
        repository.byServer(request.server, from, to) map { samples =>
          val points = GpuMetricsAggregator.aggregate(samples, step)
          HitsResult(points.length, points)
        }
    }
  }
}
