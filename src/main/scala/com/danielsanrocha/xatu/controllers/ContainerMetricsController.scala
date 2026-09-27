package com.danielsanrocha.xatu.controllers

import com.danielsanrocha.xatu.exceptions.BadArgumentException
import com.danielsanrocha.xatu.models.internals.RequestId
import com.danielsanrocha.xatu.models.requests.ContainerSamplesRequest
import com.danielsanrocha.xatu.models.responses.HitsResult
import com.danielsanrocha.xatu.repositories.ContainerMetricsRepository
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.http.Request
import com.twitter.finatra.http.Controller
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

class ContainerMetricsController(implicit val repository: ContainerMetricsRepository, implicit val ec: scala.concurrent.ExecutionContext) extends Controller {
  private val logging: Logger = Logger(this.getClass)

  private val maxRangeMillis = 8L * 24 * 3600 * 1000
  private val defaultRangeMillis = 3600L * 1000

  /** Where each container is running now (or was last seen in the last 7 days). */
  get("/api/metrics/containers") { _: Request =>
    val requestId = Contexts.local.get(RequestId).head.requestId
    logging.info(s"(x-request-id - $requestId) Container inventory route called...")
    repository.inventory() map { containers => HitsResult(containers.length, containers) }
  }

  /** Raw samples (one per minute) of a container by name, or of every container of a server. */
  get("/api/metrics/containers/samples") { request: ContainerSamplesRequest =>
    val requestId = Contexts.local.get(RequestId).head.requestId
    logging.info(s"(x-request-id - $requestId) Container samples route called for name ${request.name} server ${request.server}...")

    val to = request.to.getOrElse(System.currentTimeMillis())
    val from = request.from.getOrElse(to - defaultRangeMillis)
    val name = request.name.map(_.trim).filter(_.nonEmpty)
    val server = request.server.map(_.trim).filter(_.nonEmpty)

    val samples = (name, server) match {
      case _ if from > to                => Future.failed(new BadArgumentException("from must be before to"))
      case _ if to - from > maxRangeMillis => Future.failed(new BadArgumentException("range must be at most 8 days"))
      case (Some(n), None)               => repository.byName(n, from, to)
      case (None, Some(s))               => repository.byServer(s, from, to)
      case _                             => Future.failed(new BadArgumentException("give either name or server"))
    }
    samples map { s => HitsResult(s.length, s) }
  }
}
