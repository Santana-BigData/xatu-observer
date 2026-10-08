package com.danielsanrocha.xatu.filters

import com.typesafe.scalalogging.Logger
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.util.Future
import javax.inject.Singleton
import redis.clients.jedis.Jedis
import redis.clients.jedis.util.Pool

import com.danielsanrocha.xatu.commons.FutureConverters.RichScalaFuture
import com.danielsanrocha.xatu.models.internals.{ApiTokenAuth, RequestId, TimedCredential}
import com.danielsanrocha.xatu.models.responses.ServerMessage
import com.danielsanrocha.xatu.services.ApiTokenService

import scala.concurrent.ExecutionContext

@Singleton
/**
 * Accepts the token of a login session (Redis) or a permanent api token
 * ("Bearer xatu_..."). Read only api tokens can only make GET requests.
 */
class AuthorizeFilter(authorizationHeader: String, implicit val cachePool: Pool[Jedis], apiTokens: ApiTokenService)(implicit val ec: ExecutionContext)
    extends SimpleFilter[Request, Response] {
  private val logging = Logger(this.getClass)
  private val jsonMapper = JsonMapper.builder().addModule(DefaultScalaModule).build();

  private val readMethods = Set("GET", "HEAD", "OPTIONS")

  private def error(status: Int, message: String, requestId: String): Future[Response] = {
    val response = Response()
    response.statusCode = status
    response.setContentTypeJson()
    response.setContentString(jsonMapper.writeValueAsString(ServerMessage(message, requestId)))
    Future(response)
  }

  private def withApiToken(token: String, request: Request, service: Service[Request, Response], requestId: String): Future[Response] =
    apiTokens.authenticate(token).asTwitter.flatMap {
      case None =>
        logging.warn(s"(x-request-id - $requestId) Invalid or revoked api token ${token.take(ApiTokenService.PrefixLength)}...")
        error(401, "Invalid or revoked api token", requestId)
      case Some(t) if t.readOnly && !readMethods.contains(request.method.toString) =>
        error(403, s"Api token '${t.name}' is read only, it can only make GET requests", requestId)
      case Some(t) =>
        logging.debug(s"(x-request-id - $requestId) Authenticated with api token ${t.id} (${t.name})")
        val credential = TimedCredential(t.createdBy, s"api-token:${t.name}", System.currentTimeMillis())
        Contexts.local.let(TimedCredential, credential, ApiTokenAuth, ApiTokenAuth(t.id, t.name, t.readOnly)) { service(request) }
    }

  override def apply(request: Request, service: Service[Request, Response]): Future[Response] = {
    val requestId = Contexts.local.get(RequestId).head.requestId
    logging.debug(s"(x-request-id - ${requestId}) Checking for Authorization header...")

    request.headerMap.get(authorizationHeader) match {
      case None =>
        logging.warn(s"(x-request-id - ${requestId}) $authorizationHeader header missing, forbidden!")
        val forbiddenResponse = Response()
        forbiddenResponse.statusCode = 403
        forbiddenResponse.setContentTypeJson()
        forbiddenResponse.setContentString(jsonMapper.writeValueAsString(ServerMessage(s"Missing $authorizationHeader header", requestId)))

        Future(forbiddenResponse)

      case Some(value) if value.trim.stripPrefix("Bearer ").trim.startsWith(ApiTokenService.Prefix) =>
        withApiToken(value.trim.stripPrefix("Bearer ").trim, request, service, requestId)

      case Some(value) =>
        val header = value.trim.stripPrefix("Bearer ").trim
        val cache = cachePool.getResource
        val start = System.currentTimeMillis
        cache.get(s"xatu::token::$header") match {
          case null =>
            cache.close()
            logging.debug(s"(x-request-id - ${requestId}) User sent header Authorization: which do not exist on the cache server!")
            val errorResponse = Response()
            errorResponse.statusCode = 403
            errorResponse.setContentTypeJson()
            errorResponse.setContentString(jsonMapper.writeValueAsString(ServerMessage(s"Token was not found in the cache server! Try to login again", requestId)))
            Future(errorResponse)

          case credentialJson =>
            cache.close()
            val end = System.currentTimeMillis
            val time = (end - start).toFloat / 1000
            logging.debug(s"(x-request-id - ${requestId}) Redis took ${time} seconds to find the authorization token!")

            logging.debug(s"(x-request-id - $requestId) Token found on cache server, decoding...")
            val credential = jsonMapper.readValue(credentialJson, classOf[TimedCredential])
            Contexts.local.let(TimedCredential, credential) { service(request) }
        }
    }
  }
}
