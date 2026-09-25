package com.danielsanrocha.xatu.models

import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.models.internals.{LogContainer, LogService}
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule

import scala.concurrent.Future

class LogSpec extends UnitSpec {
  private val jsonMapper = JsonMapper.builder().addModule(DefaultScalaModule).build()

  describe("Log serialization") {
    it("should include server when set") {
      Future {
        val json = ujson.read(jsonMapper.writeValueAsString(LogContainer(1, "nginx", "hello", 10, Some("prod-1"))))
        json("server").str should equal("prod-1")
        json("container_name").str should equal("nginx")
      }
    }

    it("should omit server when not set") {
      Future {
        val json = ujson.read(jsonMapper.writeValueAsString(LogService(1, "api", "out.log", "hello", 10)))
        json.obj.contains("server") shouldBe false
        json("service_name").str should equal("api")
      }
    }
  }
}
