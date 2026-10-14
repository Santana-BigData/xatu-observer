package com.danielsanrocha.xatu.controllers

import com.danielsanrocha.xatu.commons.TelegramClient
import com.danielsanrocha.xatu.exceptions.{BadArgumentException, ForbiddenException}
import com.danielsanrocha.xatu.models.internals.{ApiTokenAuth, NewTelegramChat, RequestId, TimedCredential}
import com.danielsanrocha.xatu.models.requests.{Id, TelegramChatRequest, TelegramTestRequest}
import com.danielsanrocha.xatu.models.responses.{Created, Deleted, HitsResult, ServerMessage, TelegramChatsResponse}
import com.danielsanrocha.xatu.repositories.TelegramChatRepository
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.http.Request
import com.twitter.finatra.http.Controller
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

/**
 * Chats that receive the alerts besides TELEGRAM_CHAT_ID (which is fixed in the service
 * environment). Changing them needs a login session: an api token could add a stranger.
 */
class TelegramChatController(telegram: TelegramClient, primaryChatId: String, repository: TelegramChatRepository)(implicit val ec: scala.concurrent.ExecutionContext)
    extends Controller {
  private val logging: Logger = Logger(this.getClass)

  // read at the start of a route: the callbacks of a Future run without the request context
  private def currentRequestId: String = Contexts.local.get(RequestId).map(_.requestId).getOrElse("")
  private def currentUser: String = Contexts.local.get(TimedCredential).map(_.email).getOrElse("?")

  private def requireSession[T](f: => Future[T]): Future[T] =
    if (Contexts.local.get(ApiTokenAuth).isDefined) Future.failed(new ForbiddenException("Telegram chats are changed with a login session, not with an api token"))
    else f

  private def validated[T](c: NewTelegramChat, exceptId: Long)(f: => Future[T]): Future[T] =
    NewTelegramChat.validate(c) match {
      case Some(error) => Future.failed(new BadArgumentException(error))
      case None if c.chatId.trim == primaryChatId.trim =>
        Future.failed(new BadArgumentException("this chat already receives the alerts (TELEGRAM_CHAT_ID)"))
      case None =>
        repository.getAll().flatMap { chats =>
          if (chats.exists(x => x.chatId == c.chatId.trim && x.id != exceptId)) Future.failed(new BadArgumentException(s"chat ${c.chatId.trim} is already registered"))
          else f
        }
    }

  get("/api/telegram/chats") { _: Request =>
    repository.getAll().map(chats => TelegramChatsResponse(Option(primaryChatId).filter(_.nonEmpty), telegram.active, telegram.botUsername, chats.length, chats))
  }

  post("/api/telegram/chat") { c: NewTelegramChat =>
    val requestId = currentRequestId
    val who = currentUser
    requireSession(validated(c, 0)(repository.create(c))).map { id =>
      logging.info(s"(x-request-id - $requestId) Telegram chat $id (${c.name}, ${c.chatId}) added by $who")
      response.ok(Created(id, requestId))
    }
  }

  put("/api/telegram/chat/:id") { r: TelegramChatRequest =>
    val requestId = currentRequestId
    val who = currentUser
    val c = NewTelegramChat(r.name, r.chatId, r.enabled)
    requireSession(validated(c, r.id)(repository.update(r.id, c))).map {
      case true =>
        logging.info(s"(x-request-id - $requestId) Telegram chat ${r.id} (${c.name}, ${c.chatId}, enabled ${c.enabled}) updated by $who")
        response.ok(ServerMessage(s"Updated telegram chat ${r.id}", requestId))
      case false => response.notFound(ServerMessage(s"Telegram chat ${r.id} not found", requestId))
    }
  }

  delete("/api/telegram/chat/:id") { id: Id =>
    val requestId = currentRequestId
    val who = currentUser
    requireSession(repository.delete(id.id)).map {
      case true =>
        logging.info(s"(x-request-id - $requestId) Telegram chat ${id.id} removed by $who")
        response.ok(Deleted(id.id, requestId))
      case false => response.notFound(ServerMessage(s"Telegram chat ${id.id} not found", requestId))
    }
  }

  /** Sends a test message to a chat id (saved or not yet). */
  post("/api/telegram/test") { r: TelegramTestRequest =>
    val requestId = currentRequestId
    val who = currentUser
    requireSession {
      if (!NewTelegramChat.validate(NewTelegramChat("test", r.chatId, enabled = true)).forall(_.isEmpty)) Future.failed(new BadArgumentException("chat_id must be the number of the chat"))
      else
        Future(telegram.send(r.chatId.trim, s"✅ Test message from Xatu, sent by $who. This chat receives the alerts of the servers.")).flatMap {
          case Right(_)     => Future.successful(response.ok(ServerMessage(s"Test message sent to ${r.chatId.trim}", requestId)))
          case Left(reason) => Future.failed(new BadArgumentException(s"Telegram did not deliver to ${r.chatId.trim}: $reason"))
        }
    }
  }

  /** Chats that wrote to the bot in the last 24 hours: how to find the chat id of a new phone. */
  get("/api/telegram/recent-chats") { _: Request =>
    requireSession(Future(telegram.recentChats())).flatMap {
      case Right(chats) => Future.successful(HitsResult(chats.length, chats))
      case Left(reason) => Future.failed(new BadArgumentException(reason))
    }
  }
}
