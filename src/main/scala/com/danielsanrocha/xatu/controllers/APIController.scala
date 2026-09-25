package com.danielsanrocha.xatu.controllers

import com.danielsanrocha.xatu.models.internals.{CheckKind, NewAPI, RequestId}
import com.danielsanrocha.xatu.models.requests.{GetAll, Id, APIRequest}
import com.danielsanrocha.xatu.models.responses.{APIResponse, Created, Deleted, HitsResult, ServerMessage}
import com.danielsanrocha.xatu.repositories.StatusRepository
import com.danielsanrocha.xatu.services.{APIService}
import com.twitter.finagle.context.Contexts
import com.twitter.finatra.http.Controller
import com.typesafe.scalalogging.Logger

class APIController(implicit service: APIService, implicit val statusRepository: StatusRepository, implicit val ec: scala.concurrent.ExecutionContext)
    extends Controller {
  private val logging: Logger = Logger(this.getClass)

  get("/api/api/:id") { id: Id =>
    val requestId = Contexts.local.get(RequestId).head.requestId
    logging.info(s"(x-request-id - $requestId) GET api route called...")
    service.getById(id.id) map {
      case Some(api) => response.ok(APIResponse(api, statusRepository.checks(CheckKind.API)))
      case None      => response.notFound(ServerMessage(s"API with ${id.id} not found", requestId))
    }
  }

  post("/api/api") { api: NewAPI =>
    val requestId = Contexts.local.get(RequestId).head.requestId
    logging.info(s"(x-request-id - $requestId) POST service route called...")
    service.create(api) map { id => response.ok(Created(id, requestId)) }
  }

  put("/api/api/:id") { s: APIRequest =>
    val requestId = Contexts.local.get(RequestId).head.requestId
    logging.info(s"(x-request-id - $requestId) PUT api/:id route called...")

    service.update(s.id, NewAPI(s.name, s.host, s.port, s.healthcheckRoute)) map {
      case true  => response.ok(ServerMessage(s"Updated api with id ${s.id}", requestId))
      case false => response.notFound(ServerMessage(s"API with id ${s.id} not found", requestId))
    }
  }

  delete("/api/api/:id") { id: Id =>
    val requestId = Contexts.local.get(RequestId).head.requestId
    logging.info(s"(x-request-id - $requestId) Delete api route called...")

    service.delete(id.id) map {
      case true  => response.ok(Deleted(id.id, requestId))
      case false => response.notFound(ServerMessage(s"API with id ${id.id} not found", requestId))
    }
  }

  get("/api/apis") { request: GetAll =>
    service.getAll(request.limit, request.offset) map { apis =>
      {
        val checks = statusRepository.checks(CheckKind.API)
        response.ok(HitsResult(apis.length, apis.map(APIResponse(_, checks))))
      }
    }
  }
}
