package com.danielsanrocha.xatu.controllers

import com.danielsanrocha.xatu.commons.MetricsAggregator
import com.danielsanrocha.xatu.exceptions.BadArgumentException
import com.danielsanrocha.xatu.models.internals.RequestId
import com.danielsanrocha.xatu.models.requests.MetricsRequest
import com.danielsanrocha.xatu.models.responses.{HitsResult, MetricsResult}
import com.danielsanrocha.xatu.repositories.MetricsRepository
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.http.Request
import com.twitter.finatra.http.Controller
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

class MetricsController(intervalSeconds: Long)(implicit val repository: MetricsRepository, implicit val ec: scala.concurrent.ExecutionContext)
    extends Controller {
  private val logging: Logger = Logger(this.getClass)

  // samples live 7 days in cassandra, one extra day covers timezone and TTL edges
  private val maxRangeMillis = 8L * 24 * 3600 * 1000
  private val defaultRangeMillis = 3600L * 1000

  get("/api/metrics/servers") { _: Request =>
    val requestId = Contexts.local.get(RequestId).head.requestId
    logging.info(s"(x-request-id - $requestId) Metrics servers route called...")
    repository.servers() map { servers => HitsResult(servers.length, servers) }
  }

  get("/api/metrics") { request: MetricsRequest =>
    val requestId = Contexts.local.get(RequestId).head.requestId
    logging.info(s"(x-request-id - $requestId) Metrics route called for server ${request.server}...")

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
        repository.query(request.server, from, to) map { samples =>
          MetricsResult(request.server, from, to, step, MetricsAggregator.aggregate(samples, step))
        }
    }
  }
}
