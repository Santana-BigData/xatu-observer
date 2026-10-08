package com.danielsanrocha.xatu.filters

import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.models.internals.{ApiToken, ApiTokenAuth, TimedCredential}
import com.danielsanrocha.xatu.repositories.ApiTokenRepository
import com.danielsanrocha.xatu.services.ApiTokenService
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.http.{Request, Status}
import com.twitter.finatra.http.routing.HttpRouter
import com.twitter.finatra.http.{Controller, EmbeddedHttpServer, HttpServer}
import org.mockito.ArgumentMatchers.{any, anyLong, anyString}
import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar.mock
import redis.clients.jedis.Jedis
import redis.clients.jedis.util.Pool

import java.sql.Timestamp
import scala.concurrent.Future

class AuthorizeFilterSpec extends UnitSpec {
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

  private val readOnly = ApiTokenService.generate()
  private val full = ApiTokenService.generate()
  private val revoked = ApiTokenService.generate()

  private def server(): EmbeddedHttpServer = {
    val repository = mock[ApiTokenRepository]
    def t(id: Long, ro: Boolean) = Some(ApiToken(id, s"token-$id", "xatu_x", "h", ro, 1, new Timestamp(0), None, None))
    when(repository.getActiveByHash(anyString())).thenReturn(Future.successful(None))
    when(repository.getActiveByHash(ApiTokenService.hash(readOnly))).thenReturn(Future.successful(t(1, ro = true)))
    when(repository.getActiveByHash(ApiTokenService.hash(full))).thenReturn(Future.successful(t(2, ro = false)))
    when(repository.touch(anyLong(), any())).thenReturn(Future.unit)

    val jedis = mock[Jedis]
    val pool = mock[Pool[Jedis]]
    when(pool.getResource).thenReturn(jedis)
    when(jedis.get(anyString())).thenReturn(null)
    when(jedis.get("xatu::token::session-123")).thenReturn("""{"id":5,"email":"admin@mail.com","timestamp":0}""")

    val filter = new AuthorizeFilter("Authorization", pool, new ApiTokenService(repository))
    val controller = new Controller {
      private def who = {
        val c = Contexts.local.get(TimedCredential).head
        s"${c.id} ${c.email} token=${Contexts.local.get(ApiTokenAuth).map(_.id).getOrElse("-")}"
      }
      get("/api/who") { _: Request => who }
      post("/api/who") { _: Request => who }
    }
    new EmbeddedHttpServer(new HttpServer {
      override def configureHttp(router: HttpRouter): Unit =
        router.filter(new TestSetRequestIdFilter("test")).add(filter, controller)
    })
  }

  describe("AuthorizeFilter") {
    it("should keep accepting login sessions, with or without Bearer") {
      val s = server()
      Future {
        s.httpGet("/api/who", headers = Map("Authorization" -> "session-123"), andExpect = Status.Ok).contentString should equal("5 admin@mail.com token=-")
        s.httpGet("/api/who", headers = Map("Authorization" -> "Bearer session-123"), andExpect = Status.Ok)
        s.httpGet("/api/who", headers = Map("Authorization" -> "expired"), andExpect = Status.Forbidden)
        s.httpGet("/api/who", andExpect = Status.Forbidden)
        succeed
      }
    }

    it("should accept api tokens as Bearer and identify them") {
      val s = server()
      Future {
        s.httpGet("/api/who", headers = Map("Authorization" -> s"Bearer $readOnly"), andExpect = Status.Ok).contentString should equal("1 api-token:token-1 token=1")
        s.httpPost("/api/who", "{}", headers = Map("Authorization" -> s"Bearer $full"), andExpect = Status.Ok).contentString should equal("1 api-token:token-2 token=2")
      }
    }

    it("should refuse revoked tokens and writes with read only tokens") {
      val s = server()
      Future {
        s.httpGet("/api/who", headers = Map("Authorization" -> s"Bearer $revoked"), andExpect = Status.Unauthorized)
        s.httpPost("/api/who", "{}", headers = Map("Authorization" -> s"Bearer $readOnly"), andExpect = Status.Forbidden).contentString should include("read only")
        succeed
      }
    }
  }
}
