package com.danielsanrocha.xatu.repositories

import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.exceptions.BadArgumentException

class LogRepositorySpec extends UnitSpec {
  private val repository = new LogRepositoryImpl("elasticsearch", scala.concurrent.ExecutionContext.global)

  describe("search method") {
    it("should reject size lower than 1") {
      recoverToSucceededIf[BadArgumentException](repository.search("message:test", Some(0)))
    }

    it("should reject size greater than 10000") {
      recoverToSucceededIf[BadArgumentException](repository.search("message:test", Some(10001)))
    }

    it("should reject query with double quotes") {
      recoverToSucceededIf[BadArgumentException](repository.search("message:\"test\"", None))
    }
  }
}
