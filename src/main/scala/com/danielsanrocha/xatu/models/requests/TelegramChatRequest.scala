package com.danielsanrocha.xatu.models.requests

import com.twitter.finatra.http.annotations.RouteParam

case class TelegramChatRequest(@RouteParam id: Long, name: String, chatId: String, enabled: Boolean)

case class TelegramTestRequest(chatId: String)
