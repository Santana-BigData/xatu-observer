package com.danielsanrocha.xatu.controllers

import com.danielsanrocha.xatu.models.internals.{CheckKind, NewService, RequestId}
import com.danielsanrocha.xatu.models.requests.{GetAll, Id, ServiceRequest}
import com.danielsanrocha.xatu.models.responses.{Created, Deleted, HitsResult, ServerMessage, ServiceResponse}
import com.danielsanrocha.xatu.repositories.StatusRepository
import com.danielsanrocha.xatu.services.ServiceService
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.http.Request
import com.twitter.finatra.http.Controller
import com.typesafe.scalalogging.Logger

class ServiceController(implicit service: ServiceService, implicit val statusRepository: StatusRepository, implicit val ec: scala.concurrent.ExecutionContext)
    extends Controller {
  private val logging: Logger = Logger(this.getClass)

  get("/api/service/:id") { id: Id =>
    val requestId = Contexts.local.get(RequestId).head.requestId
    logging.info(s"(x-request-id - $requestId) GET service route called...")
    service.getById(id.id) map {
      case Some(service) => response.ok(ServiceResponse(service, statusRepository.checks(CheckKind.Service)))
      case None          => response.notFound(ServerMessage(s"Service with ${id.id} not found", requestId))
    }
  }

  post("/api/service") { s: NewService =>
    val requestId = Contexts.local.get(RequestId).head.requestId
    logging.info(s"(x-request-id - $requestId) POST service route called...")
    service.create(s) map { id => response.ok(Created(id, requestId)) }
  }

  put("/api/service/:id") { s: ServiceRequest =>
    val requestId = Contexts.local.get(RequestId).head.requestId
    logging.info(s"(x-request-id - $requestId) PUT service/:id route called...")

    service.update(s.id, NewService(s.name, s.logFileDirectory, s.logFileRegex, s.pidFile)) map {
      case true  => response.ok(ServerMessage(s"Updated service with id ${s.id}", requestId))
      case false => response.notFound(ServerMessage(s"Service with id ${s.id} not found", requestId))
    }
  }

  delete("/api/service/:id") { id: Id =>
    val requestId = Contexts.local.get(RequestId).head.requestId
    logging.info(s"(x-request-id - $requestId) Delete service route called...")

    service.delete(id.id) map {
      case true  => response.ok(Deleted(id.id, requestId))
      case false => response.notFound(ServerMessage(s"Service with id ${id.id} not found", requestId))
    }
  }

  get("/api/services") { request: GetAll =>
    service.getAll(request.limit, request.offset) map { services =>
      {
        val checks = statusRepository.checks(CheckKind.Service)
        response.ok(HitsResult(services.length, services.map(ServiceResponse(_, checks))))
      }
    }
  }
}
