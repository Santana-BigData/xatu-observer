package com.danielsanrocha.xatu.controllers

import com.danielsanrocha.xatu.exceptions.{BadArgumentException, ForbiddenException}
import com.danielsanrocha.xatu.models.internals.{ApiTokenAuth, RequestId, TimedCredential}
import com.danielsanrocha.xatu.models.requests.{Id, NewApiToken}
import com.danielsanrocha.xatu.models.responses.{ApiTokenResponse, CreatedApiToken, HitsResult, ServerMessage}
import com.danielsanrocha.xatu.services.ApiTokenService
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.http.Request
import com.twitter.finatra.http.Controller
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

/** Management of permanent api tokens. Only a login session can manage them, never a token. */
class ApiTokenController(apiTokens: ApiTokenService)(implicit val ec: scala.concurrent.ExecutionContext) extends Controller {
  private val logging: Logger = Logger(this.getClass)

  private def requireSession[T](f: => Future[T]): Future[T] =
    if (Contexts.local.get(ApiTokenAuth).isDefined) Future.failed(new ForbiddenException("Api tokens are managed with a login session, not with an api token"))
    else f

  get("/api/tokens") { _: Request =>
    val requestId = Contexts.local.get(RequestId).head.requestId
    logging.info(s"(x-request-id - $requestId) List api tokens route called...")
    requireSession(apiTokens.list().map(tokens => HitsResult(tokens.length, tokens.map(ApiTokenResponse(_)))))
  }

  post("/api/tokens") { request: NewApiToken =>
    val requestId = Contexts.local.get(RequestId).head.requestId
    val credential = Contexts.local.get(TimedCredential).head
    logging.info(s"(x-request-id - $requestId) Create api token route called by ${credential.email}...")
    val name = request.name.trim
    requireSession {
      if (name.isEmpty || name.length > 200) Future.failed(new BadArgumentException("name is required (up to 200 characters)"))
      else
        apiTokens.create(name, request.readOnly, credential.id).map { case (id, token) =>
          logging.info(s"(x-request-id - $requestId) Api token $id ($name, read only ${request.readOnly}) created by ${credential.email}")
          CreatedApiToken(id, name, token, request.readOnly, requestId)
        }
    }
  }

  delete("/api/tokens/:id") { id: Id =>
    val requestId = Contexts.local.get(RequestId).head.requestId
    logging.info(s"(x-request-id - $requestId) Revoke api token ${id.id} route called...")
    requireSession(apiTokens.revoke(id.id)).map {
      case true  => response.ok(ServerMessage(s"Api token ${id.id} revoked", requestId))
      case false => response.notFound(ServerMessage(s"Active api token ${id.id} not found", requestId))
    }
  }
}
