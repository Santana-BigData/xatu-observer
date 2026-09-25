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

import com.danielsanrocha.xatu.models.internals.{RequestId, TimedCredential}
import com.danielsanrocha.xatu.models.responses.ServerMessage

@Singleton
class AuthorizeFilter(authorizationHeader: String, implicit val cachePool: Pool[Jedis]) extends SimpleFilter[Request, Response] {
  private val logging = Logger(this.getClass)
  private val jsonMapper = JsonMapper.builder().addModule(DefaultScalaModule).build();

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

      case Some(header) =>
        val cache = cachePool.getResource
        val start = System.currentTimeMillis
        cache.get(s"token:$header") match {
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
