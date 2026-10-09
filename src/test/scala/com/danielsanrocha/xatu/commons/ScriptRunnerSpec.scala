package com.danielsanrocha.xatu.commons

import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.commons.ScriptRunner.Result

import scala.concurrent.Future

class ScriptRunnerSpec extends UnitSpec {
  describe("run") {
    it("should succeed with exit 0 and keep the output") {
      Future {
        val result = ScriptRunner.run("echo hello; echo oops >&2", 10)
        result.ok shouldBe true
        result.output should include("hello")
        result.output should include("oops")
      }
    }

    it("should fail with the exit code and pass the environment") {
      Future {
        val result = ScriptRunner.run("""echo "on $XATU_SERVER"; exit 3""", 10, Map("XATU_SERVER" -> "s1"))
        result.exitCode should equal(Some(3))
        result.output.trim should equal("on s1")
      }
    }

    it("should kill a script, and its children, that runs longer than the timeout") {
      Future {
        val started = System.currentTimeMillis()
        val result = ScriptRunner.run("sleep 30 & sleep 30; echo never", 1)
        result.exitCode should equal(None)
        result.output should not include "never"
        (System.currentTimeMillis() - started) should be < 10000L
      }
    }

    it("should not wait for stdin") {
      Future {
        ScriptRunner.run("cat", 5).ok shouldBe true
      }
    }

    it("should keep only the end of a long output") {
      Future {
        val result = ScriptRunner.run("seq 1 100000", 10)
        result.ok shouldBe true
        result.output.length should be <= 8000
        result.output.trim should endWith("100000")
      }
    }
  }

  describe("failureMessage") {
    it("should give the exit code and the last lines") {
      Future {
        ScriptRunner.failureMessage(Result(Some(2), "a\nb\n\nc\nd\n", 10), 30) should equal("exit 2: b | c | d")
        ScriptRunner.failureMessage(Result(Some(1), "", 10), 30) should equal("exit 1")
        ScriptRunner.failureMessage(Result(None, "waiting", 10), 30) should equal("timed out after 30s: waiting")
        ScriptRunner.failureMessage(Result(Some(1), "x" * 1000, 10), 30, maxLength = 10) should equal("exit 1: ..." + "x" * 10)
      }
    }
  }
}
