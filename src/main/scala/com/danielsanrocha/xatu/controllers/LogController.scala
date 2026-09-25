package com.danielsanrocha.xatu.controllers

import com.twitter.finagle.context.Contexts
import com.twitter.finatra.http.Controller
import com.typesafe.scalalogging.Logger
import com.danielsanrocha.xatu.models.internals.RequestId
import com.danielsanrocha.xatu.models.requests.LogSearchRequest
import com.danielsanrocha.xatu.models.responses.HitsResult
import com.danielsanrocha.xatu.services.LogService

class LogController(implicit val service: LogService, implicit val ec: scala.concurrent.ExecutionContext) extends Controller {
  private val logging: Logger = Logger(this.getClass)

  get("/api/logs") { request: LogSearchRequest =>
    val requestId = Contexts.local.get(RequestId).head.requestId
    logging.info(s"(x-request-id - $requestId) Search logs called, returning result...")
    service.search(request.query, request.size) map { result =>
      HitsResult(result.length, result)
    }
  }
}
