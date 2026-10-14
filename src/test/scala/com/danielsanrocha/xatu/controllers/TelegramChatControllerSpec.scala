package com.danielsanrocha.xatu.controllers

import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.commons.TelegramClient
import com.danielsanrocha.xatu.filters.{ExceptionHandlerFilter, TestSetCredentialFilter, TestSetRequestIdFilter}
import com.danielsanrocha.xatu.models.internals.{ApiTokenAuth, NewTelegramChat, TelegramChat, TimedCredential}
import com.danielsanrocha.xatu.repositories.TelegramChatRepository
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

class TelegramChatControllerSpec extends UnitSpec with TestController {
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
  private val credential = TimedCredential(5, "admin@mail.com", 0)
  private val ana = TelegramChat(1, "Ana", "111", enabled = true, new Timestamp(0), new Timestamp(0))

  private def repository() = {
    val r = mock[TelegramChatRepository]
    when(r.getAll()).thenReturn(Future.successful(Seq(ana)))
    when(r.create(any[NewTelegramChat]())).thenReturn(Future.successful(2L))
    r
  }

  private val inactive = new TelegramClient("inactive")

  describe("telegram chat routes") {
    it("should list the chats with TELEGRAM_CHAT_ID apart") {
      Future {
        val json = ujson.read(createServer(new TelegramChatController(inactive, "2070462929", repository()), credential).httpGet("/api/telegram/chats", andExpect = Status.Ok).contentString)
        (json("primary").str, json("hits")(0)("chat_id").str, json("active").bool) should equal(("2070462929", "111", false))
      }
    }

    it("should add a chat, refusing a repeated one, TELEGRAM_CHAT_ID and invalid ids") {
      val repo = repository()
      val server = createServer(new TelegramChatController(inactive, "2070462929", repo), credential)
      Future {
        ujson.read(server.httpPost("/api/telegram/chat", """{"name": "Bruno", "chat_id": "222", "enabled": true}""", andExpect = Status.Ok).contentString)("id").num should equal(2)
        server.httpPost("/api/telegram/chat", """{"name": "Ana again", "chat_id": "111", "enabled": true}""", andExpect = Status.BadRequest)
        server.httpPost("/api/telegram/chat", """{"name": "Me", "chat_id": "2070462929", "enabled": true}""", andExpect = Status.BadRequest)
        server.httpPost("/api/telegram/chat", """{"name": "Bob", "chat_id": "@bob", "enabled": true}""", andExpect = Status.BadRequest)
        // editing a chat keeping its own id is not a repetition
        when(repo.update(org.mockito.ArgumentMatchers.eq(1L), any[NewTelegramChat]())).thenReturn(Future.successful(true))
        server.httpPut("/api/telegram/chat/1", """{"name": "Ana S", "chat_id": "111", "enabled": false}""", andExpect = Status.Ok)
        succeed
      }
    }

    it("should tell why a test message was not sent") {
      Future {
        val body = createServer(new TelegramChatController(inactive, "", repository()), credential)
          .httpPost("/api/telegram/test", """{"chat_id": "111"}""", andExpect = Status.BadRequest)
          .contentString
        body should include("not configured")
      }
    }

    it("should refuse changes with an api token") {
      val repo = repository()
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
            .add(new TelegramChatController(inactive, "2070462929", repo))
      })
      Future {
        server.httpPost("/api/telegram/chat", """{"name": "Bruno", "chat_id": "222", "enabled": true}""", andExpect = Status.Forbidden)
        server.httpDelete("/api/telegram/chat/1", andExpect = Status.Forbidden)
        server.httpPost("/api/telegram/test", """{"chat_id": "222"}""", andExpect = Status.Forbidden)
        server.httpGet("/api/telegram/recent-chats", andExpect = Status.Forbidden)
        verify(repo, never()).create(any[NewTelegramChat]())
        succeed
      }
    }
  }
}
