package com.danielsanrocha.xatu.models.internals

import java.sql.Timestamp

/** A telegram chat (a phone or a group) that receives the alerts, besides TELEGRAM_CHAT_ID. */
case class TelegramChat(
    id: Long,
    name: String,
    chatId: String,
    enabled: Boolean,
    createDate: Timestamp,
    updateDate: Timestamp
)

case class NewTelegramChat(name: String, chatId: String, enabled: Boolean)

object NewTelegramChat {
  // user ids are positive, groups negative (supergroups -100...)
  private val ChatId = """-?\d{1,20}""".r

  def validate(c: NewTelegramChat): Option[String] =
    if (c.name.trim.isEmpty || c.name.length > 200) Some("name is required (up to 200 characters)")
    else if (!ChatId.matches(c.chatId.trim)) Some("chat_id must be the number of the chat, e.g. 2070462929 (a group starts with -)")
    else None
}

/** A chat that wrote to the bot recently, to find the chat id of a new phone. */
case class TelegramRecentChat(chatId: String, name: String, kind: String, lastMessage: String, at: Long)
