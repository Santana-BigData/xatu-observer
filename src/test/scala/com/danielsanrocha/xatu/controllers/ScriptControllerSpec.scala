package com.danielsanrocha.xatu.controllers

import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.filters.{ExceptionHandlerFilter, TestSetCredentialFilter, TestSetRequestIdFilter}
import com.danielsanrocha.xatu.models.internals.{ApiTokenAuth, NewScript, Script, ServerCheck, TimedCredential}
import com.danielsanrocha.xatu.repositories.{ScriptRepository, StatusRepository}
import com.danielsanrocha.xatu.services.{ScriptService, ScriptServiceImpl}
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.finatra.http.routing.HttpRouter
import com.twitter.finatra.http.{EmbeddedHttpServer, HttpServer}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{never, verify, when}
import org.scalatestplus.mockito.MockitoSugar.mock

import java.sql.Timestamp
import scala.concurrent.Future

class ScriptControllerSpec extends UnitSpec with TestController {
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
  private val credential = TimedCredential(5, "admin@mail.com", 0)
  private val backup = Script(5, "backup", "test -f /backup/ok", 300, 30, new Timestamp(0), new Timestamp(0))

  describe("GET /api/scripts") {
    it("should return the script with the status of each server") {
      implicit val service: ScriptService = mock[ScriptService]
      implicit val statusRepository: StatusRepository = mock[StatusRepository]
      when(service.getAll(100, 0)).thenReturn(Future.successful(Seq(backup)))
      when(statusRepository.checks("script")).thenReturn(Map(5L -> Seq(ServerCheck("script", 5, "s1", 'F', Some("exit 1"), 1))))
      val server = createServer(new ScriptController(), credential)

      Future {
        val hit = ujson.read(server.httpGet("/api/scripts?limit=100&offset=0", andExpect = Status.Ok).contentString)("hits")(0)
        hit("script").str should equal("test -f /backup/ok")
        hit("interval_seconds").num should equal(300)
        hit("status").str should equal("F")
        hit("servers")(0)("message").str should equal("exit 1")
      }
    }
  }

  describe("POST /api/script") {
    it("should create a valid script with a login session") {
      implicit val repository: ScriptRepository = mock[ScriptRepository]
      implicit val service: ScriptService = new ScriptServiceImpl()
      implicit val statusRepository: StatusRepository = mock[StatusRepository]
      val script = NewScript("backup", "test -f /backup/ok", 300, 30)
      when(repository.create(script)).thenReturn(Future.successful(7L))
      val server = createServer(new ScriptController(), credential)

      Future {
        val body = """{"name": "backup", "script": "test -f /backup/ok", "interval_seconds": 300, "timeout_seconds": 30}"""
        ujson.read(server.httpPost("/api/script", body, andExpect = Status.Ok).contentString)("id").num should equal(7)
        server.httpPost("/api/script", """{"name": "x", "script": "true", "interval_seconds": 1, "timeout_seconds": 1}""", andExpect = Status.BadRequest)
        succeed
      }
    }

    it("should refuse to create, edit or remove scripts with an api token") {
      implicit val repository: ScriptRepository = mock[ScriptRepository]
      implicit val service: ScriptService = new ScriptServiceImpl()
      implicit val statusRepository: StatusRepository = mock[StatusRepository]
      val asToken = new SimpleFilter[Request, Response] {
        override def apply(request: Request, service: Service[Request, Response]) =
          Contexts.local.let(ApiTokenAuth, ApiTokenAuth(9, "ci", readOnly = false)) { service(request) }
      }
      val server = new EmbeddedHttpServer(new HttpServer {
        override def configureHttp(router: HttpRouter): Unit =
          router
            .filter(new TestSetRequestIdFilter("test"))
            .filter(new ExceptionHandlerFilter())
            .filter(new TestSetCredentialFilter(credential))
            .filter(asToken)
            .add(new ScriptController())
      })

      Future {
        val body = """{"name": "backup", "script": "true", "interval_seconds": 60, "timeout_seconds": 30}"""
        server.httpPost("/api/script", body, andExpect = Status.Forbidden)
        server.httpPut("/api/script/5", body, andExpect = Status.Forbidden)
        server.httpDelete("/api/script/5", andExpect = Status.Forbidden)
        verify(repository, never()).create(any())
        verify(repository, never()).delete(5)
        succeed
      }
    }
  }
}
