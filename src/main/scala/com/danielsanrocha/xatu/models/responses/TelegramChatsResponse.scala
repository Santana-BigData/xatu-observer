package com.danielsanrocha.xatu.models.responses

import com.danielsanrocha.xatu.models.internals.TelegramChat

/** `primary` is TELEGRAM_CHAT_ID, which always receives the alerts and is not editable here. */
case class TelegramChatsResponse(primary: Option[String], active: Boolean, bot: Option[String], count: Int, hits: Seq[TelegramChat])
