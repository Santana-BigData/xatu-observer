package com.danielsanrocha.xatu.controllers

import com.danielsanrocha.xatu.exceptions.ForbiddenException
import com.danielsanrocha.xatu.models.internals.{ApiTokenAuth, CheckKind, NewScript, RequestId, TimedCredential}
import com.danielsanrocha.xatu.models.requests.{GetAll, Id, ScriptRequest}
import com.danielsanrocha.xatu.models.responses.{Created, Deleted, HitsResult, ScriptResponse, ServerMessage}
import com.danielsanrocha.xatu.repositories.StatusRepository
import com.danielsanrocha.xatu.services.ScriptService
import com.twitter.finagle.context.Contexts
import com.twitter.finatra.http.Controller
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

/**
 * Scripts run on every server, so creating, editing and removing them needs a login
 * session: an api token (even a full one) can only read them.
 */
class ScriptController(implicit service: ScriptService, implicit val statusRepository: StatusRepository, implicit val ec: scala.concurrent.ExecutionContext)
    extends Controller {
  private val logging: Logger = Logger(this.getClass)

  private def requireSession[T](f: => Future[T]): Future[T] =
    if (Contexts.local.get(ApiTokenAuth).isDefined) Future.failed(new ForbiddenException("Scripts are changed with a login session, not with an api token"))
    else f

  private def who: String = Contexts.local.get(TimedCredential).map(_.email).getOrElse("?")

  get("/api/script/:id") { id: Id =>
    val requestId = Contexts.local.get(RequestId).head.requestId
    logging.info(s"(x-request-id - $requestId) GET script route called...")
    service.getById(id.id) map {
      case Some(script) => response.ok(ScriptResponse(script, statusRepository.checks(CheckKind.Script)))
      case None         => response.notFound(ServerMessage(s"Script with ${id.id} not found", requestId))
    }
  }

  post("/api/script") { s: NewScript =>
    val requestId = Contexts.local.get(RequestId).head.requestId
    logging.info(s"(x-request-id - $requestId) POST script route called by $who...")
    requireSession(service.create(s)) map { id =>
      logging.info(s"(x-request-id - $requestId) Script $id (${s.name}) created by $who")
      response.ok(Created(id, requestId))
    }
  }

  put("/api/script/:id") { s: ScriptRequest =>
    val requestId = Contexts.local.get(RequestId).head.requestId
    logging.info(s"(x-request-id - $requestId) PUT script/:id route called by $who...")
    requireSession(service.update(s.id, NewScript(s.name, s.script, s.intervalSeconds, s.timeoutSeconds))) map {
      case true =>
        logging.info(s"(x-request-id - $requestId) Script ${s.id} (${s.name}) updated by $who")
        response.ok(ServerMessage(s"Updated script with id ${s.id}", requestId))
      case false => response.notFound(ServerMessage(s"Script with id ${s.id} not found", requestId))
    }
  }

  delete("/api/script/:id") { id: Id =>
    val requestId = Contexts.local.get(RequestId).head.requestId
    logging.info(s"(x-request-id - $requestId) Delete script route called by $who...")
    requireSession(service.delete(id.id)) map {
      case true  => response.ok(Deleted(id.id, requestId))
      case false => response.notFound(ServerMessage(s"Script with id ${id.id} not found", requestId))
    }
  }

  get("/api/scripts") { request: GetAll =>
    service.getAll(request.limit, request.offset) map { scripts =>
      val checks = statusRepository.checks(CheckKind.Script)
      response.ok(HitsResult(scripts.length, scripts.map(ScriptResponse(_, checks))))
    }
  }
}
