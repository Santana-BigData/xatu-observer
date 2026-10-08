package com.danielsanrocha.xatu.controllers

import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.filters.{ExceptionHandlerFilter, TestSetCredentialFilter, TestSetRequestIdFilter}
import com.danielsanrocha.xatu.models.internals.{ApiToken, ApiTokenAuth, TimedCredential}
import com.danielsanrocha.xatu.repositories.ApiTokenRepository
import com.danielsanrocha.xatu.services.ApiTokenService
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.finatra.http.routing.HttpRouter
import com.twitter.finatra.http.{EmbeddedHttpServer, HttpServer}
import org.mockito.ArgumentMatchers.{anyBoolean, anyLong, anyString}
import org.mockito.Mockito.{never, verify, when}
import org.scalatestplus.mockito.MockitoSugar.mock

import java.sql.Timestamp
import scala.concurrent.Future

class ApiTokenControllerSpec extends UnitSpec with TestController {
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
  private val credential = TimedCredential(5, "admin@mail.com", 0)

  describe("POST /api/tokens") {
    it("should create a token and show it once") {
      val repository = mock[ApiTokenRepository]
      when(repository.create(anyString(), anyString(), anyString(), anyBoolean(), anyLong())).thenReturn(Future.successful(9L))
      val server = createServer(new ApiTokenController(new ApiTokenService(repository)), credential)

      Future {
        val json = ujson.read(server.httpPost("/api/tokens", """{"name": "grafana", "read_only": true}""", andExpect = Status.Ok).contentString)
        json("id").num should equal(9)
        json("token").str should startWith("xatu_")
        verify(repository).create(org.mockito.ArgumentMatchers.eq("grafana"), anyString(), anyString(), org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.eq(5L))
        succeed
      }
    }

    it("should reject an empty name") {
      val repository = mock[ApiTokenRepository]
      val server = createServer(new ApiTokenController(new ApiTokenService(repository)), credential)
      Future {
        server.httpPost("/api/tokens", """{"name": "  "}""", andExpect = Status.BadRequest)
        verify(repository, never()).create(anyString(), anyString(), anyString(), anyBoolean(), anyLong())
        succeed
      }
    }
  }

  describe("GET /api/tokens") {
    it("should list tokens without the hash") {
      val repository = mock[ApiTokenRepository]
      when(repository.getAll()).thenReturn(
        Future.successful(Seq(ApiToken(9, "grafana", "xatu_abcdef", "secret-hash", readOnly = true, 5, new Timestamp(0), None, Some(new Timestamp(1)))))
      )
      val server = createServer(new ApiTokenController(new ApiTokenService(repository)), credential)
      Future {
        val body = server.httpGet("/api/tokens", andExpect = Status.Ok).contentString
        body should not include "secret-hash"
        val hit = ujson.read(body)("hits")(0)
        (hit("token_prefix").str, hit("active").bool, hit("read_only").bool) should equal(("xatu_abcdef", false, true))
      }
    }

    it("should refuse to manage tokens with an api token") {
      val repository = mock[ApiTokenRepository]
      val asToken = new SimpleFilter[Request, Response] {
        override def apply(request: Request, service: Service[Request, Response]) =
          Contexts.local.let(ApiTokenAuth, ApiTokenAuth(9, "grafana", readOnly = false)) { service(request) }
      }
      val server = new EmbeddedHttpServer(new HttpServer {
        override def configureHttp(router: HttpRouter): Unit =
          router
            .filter(new TestSetRequestIdFilter("test"))
            .filter(new ExceptionHandlerFilter())
            .filter(new TestSetCredentialFilter(credential))
            .filter(asToken)
            .add(new ApiTokenController(new ApiTokenService(repository)))
      })
      Future {
        server.httpGet("/api/tokens", andExpect = Status.Forbidden)
        server.httpPost("/api/tokens", """{"name": "x"}""", andExpect = Status.Forbidden)
        verify(repository, never()).getAll()
        succeed
      }
    }
  }
}
