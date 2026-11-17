package com.danielsanrocha.xatu.commons

import com.danielsanrocha.xatu.UnitSpec

import scala.concurrent.Future

class DockerAvailabilitySpec extends UnitSpec {
  describe("configured") {
    it("should find docker by DOCKER_HOST or by the unix socket") {
      Future {
        DockerAvailability.configured(Some("tcp://127.0.0.1:2375"), socketExists = false) shouldBe true
        DockerAvailability.configured(None, socketExists = true) shouldBe true
        DockerAvailability.configured(Some(" "), socketExists = false) shouldBe false
        DockerAvailability.configured(None, socketExists = false) shouldBe false
      }
    }
  }
}
