package com.danielsanrocha.xatu.commons

import com.danielsanrocha.xatu.models.internals.TelegramRecentChat
import scalaj.http.{Http, HttpOptions}

import scala.util.Try

/** Calls to the telegram bot api. `token` "inactive" means telegram is not configured. */
class TelegramClient(token: String) {

  def active: Boolean = token.nonEmpty && token != "inactive"

  /** Sends `text` to a chat. Left with the reason when telegram refuses it. */
  def send(chatId: String, text: String): Either[String, Unit] =
    if (!active) Left("telegram is not configured (TELEGRAM_BOT_TOKEN)")
    else
      Try(
        Http(s"https://api.telegram.org/bot$token/sendMessage")
          .param("chat_id", chatId)
          .param("text", text)
          .option(HttpOptions.connTimeout(10000))
          .option(HttpOptions.readTimeout(10000))
          .execute()
      ).toEither.left
        .map(e => s"could not reach telegram: ${e.getMessage}")
        .flatMap(r => if (r.code == 200) Right(()) else Left(TelegramClient.description(r.body).getOrElse(s"telegram answered ${r.code}")))

  /** Username of the bot (to tell people which bot to talk to), read once. None when it can not be read. */
  lazy val botUsername: Option[String] =
    if (!active) None
    else
      Try(
        Http(s"https://api.telegram.org/bot$token/getMe")
          .option(HttpOptions.connTimeout(10000))
          .option(HttpOptions.readTimeout(10000))
          .execute()
      ).toOption.filter(_.code == 200).flatMap(r => Try(ujson.read(r.body)("result")("username").str).toOption)

  /** Chats that wrote to the bot in the last 24 hours (telegram keeps the updates for a day). */
  def recentChats(): Either[String, Seq[TelegramRecentChat]] =
    if (!active) Left("telegram is not configured (TELEGRAM_BOT_TOKEN)")
    else
      Try(
        Http(s"https://api.telegram.org/bot$token/getUpdates")
          .option(HttpOptions.connTimeout(10000))
          .option(HttpOptions.readTimeout(10000))
          .execute()
      ).toEither.left
        .map(e => s"could not reach telegram: ${e.getMessage}")
        .flatMap(r => if (r.code == 200) Right(TelegramClient.parseUpdates(r.body)) else Left(TelegramClient.description(r.body).getOrElse(s"telegram answered ${r.code}")))
}

object TelegramClient {

  /** The "description" of an error answer, e.g. "Bad Request: chat not found". */
  def description(body: String): Option[String] = Try(ujson.read(body)("description").str).toOption

  /** Last message of each chat in a getUpdates answer, newest first. */
  def parseUpdates(body: String): Seq[TelegramRecentChat] =
    Try(ujson.read(body)("result").arr.toSeq).getOrElse(Seq()).flatMap { update =>
      val obj = update.obj
      Seq("message", "edited_message", "channel_post", "my_chat_member").flatMap(obj.get).headOption.flatMap { m =>
        Try {
          val chat = m("chat")
          val first = chat.obj.get("first_name").map(_.str)
          val last = chat.obj.get("last_name").map(_.str)
          val name = chat.obj.get("title").map(_.str)
            .orElse(Some(Seq(first, last).flatten.mkString(" ")).filter(_.nonEmpty))
            .orElse(chat.obj.get("username").map("@" + _.str))
            .getOrElse("")
          TelegramRecentChat(
            chatId = chat("id").num.toLong.toString,
            name = name,
            kind = chat.obj.get("type").map(_.str).getOrElse(""),
            lastMessage = m.obj.get("text").map(_.str).getOrElse(""),
            at = m.obj.get("date").map(_.num.toLong * 1000).getOrElse(0L)
          )
        }.toOption
      }
    }.groupBy(_.chatId).values.map(_.maxBy(_.at)).toSeq.sortBy(-_.at)
}
