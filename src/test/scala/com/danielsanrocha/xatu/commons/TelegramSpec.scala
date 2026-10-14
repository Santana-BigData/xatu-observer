package com.danielsanrocha.xatu.commons

import com.danielsanrocha.xatu.TelegramNotifier
import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.models.internals.{NewTelegramChat, TelegramChat, TelegramRecentChat}

import java.sql.Timestamp
import scala.concurrent.Future

class TelegramSpec extends UnitSpec {
  private def chat(id: Long, chatId: String, enabled: Boolean = true) = TelegramChat(id, s"chat $id", chatId, enabled, new Timestamp(0), new Timestamp(0))

  describe("recipients") {
    it("should send to TELEGRAM_CHAT_ID and the enabled chats, once each") {
      Future {
        TelegramNotifier.recipients("2070462929", Seq(chat(1, "111"), chat(2, "222", enabled = false), chat(3, " 2070462929 "), chat(4, "-1001234"))) should equal(
          Seq("2070462929", "111", "-1001234")
        )
        TelegramNotifier.recipients("", Seq(chat(1, "111"))) should equal(Seq("111"))
      }
    }
  }

  describe("parseUpdates") {
    // getUpdates after two people wrote /start and the bot was added to a group
    val body =
      """{"ok":true,"result":[
        |{"update_id":1,"message":{"message_id":5,"from":{"id":111,"first_name":"Ana"},"chat":{"id":111,"first_name":"Ana","last_name":"Souza","type":"private"},"date":1760000000,"text":"/start"}},
        |{"update_id":2,"message":{"message_id":6,"chat":{"id":111,"first_name":"Ana","last_name":"Souza","type":"private"},"date":1760000100,"text":"oi"}},
        |{"update_id":3,"my_chat_member":{"chat":{"id":-1001234,"title":"Infra","type":"supergroup"},"date":1760000050}},
        |{"update_id":4,"message":{"message_id":7,"chat":{"id":222,"username":"bob","type":"private"},"date":1759999000,"text":"/start"}}
        |]}""".stripMargin

    it("should list each chat once with its last message, newest first") {
      Future {
        TelegramClient.parseUpdates(body) should equal(
          Seq(
            TelegramRecentChat("111", "Ana Souza", "private", "oi", 1760000100000L),
            TelegramRecentChat("-1001234", "Infra", "supergroup", "", 1760000050000L),
            TelegramRecentChat("222", "@bob", "private", "/start", 1759999000000L)
          )
        )
      }
    }

    it("should read the reason of an error answer") {
      Future {
        TelegramClient.description("""{"ok":false,"error_code":400,"description":"Bad Request: chat not found"}""") should equal(Some("Bad Request: chat not found"))
        TelegramClient.parseUpdates("not json") shouldBe empty
      }
    }
  }

  describe("NewTelegramChat.validate") {
    it("should accept user and group ids only") {
      Future {
        NewTelegramChat.validate(NewTelegramChat("Ana", "2070462929", enabled = true)) shouldBe None
        NewTelegramChat.validate(NewTelegramChat("Infra", "-1001234567890", enabled = true)) shouldBe None
        NewTelegramChat.validate(NewTelegramChat("Ana", "@ana", enabled = true)) shouldBe defined
        NewTelegramChat.validate(NewTelegramChat(" ", "111", enabled = true)) shouldBe defined
      }
    }
  }
}
