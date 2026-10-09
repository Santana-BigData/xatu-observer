package com.danielsanrocha.xatu.observers

import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.commons.ScriptRunner.Result
import com.danielsanrocha.xatu.models.internals.{NewScript, Script, ServerCheck}
import com.danielsanrocha.xatu.repositories.StatusRepository
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{eq => same}
import org.mockito.Mockito.verify
import org.scalatestplus.mockito.MockitoSugar.mock

import java.sql.Timestamp
import scala.concurrent.Future

class ScriptObserverSpec extends UnitSpec {
  private val backup = Script(5, "backup", "test -f /backup/ok", 300, 30, new Timestamp(0), new Timestamp(0))

  private def checkAfter(result: Result): (ServerCheck, Map[String, String]) = {
    val statusRepository = mock[StatusRepository]
    var env = Map[String, String]()
    val observer = new ScriptObserver(backup, "s1", statusRepository, (_, e) => { env = e; result })
    observer.task.run()
    val captor = ArgumentCaptor.forClass(classOf[ServerCheck])
    // the check lasts until the next run ends
    verify(statusRepository).save(captor.capture(), same(390L))
    (captor.getValue, env)
  }

  describe("task") {
    it("should save working when the script exits 0, telling it the server") {
      Future {
        val (check, env) = checkAfter(Result(Some(0), "ok", 10))
        (check.kind, check.targetId, check.server, check.status, check.message) should equal(("script", 5L, "s1", 'W', None))
        env should equal(Map("XATU_SERVER" -> "s1", "XATU_SCRIPT_NAME" -> "backup"))
      }
    }

    it("should save failing with the reason on a non-zero exit or timeout") {
      Future {
        checkAfter(Result(Some(1), "no backup today\n", 10))._1.message should equal(Some("exit 1: no backup today"))
        checkAfter(Result(None, "", 30000))._1.message should equal(Some("timed out after 30s"))
      }
    }

    it("should run every interval_seconds") {
      Future {
        val observer = new ScriptObserver(backup, "s1", mock[StatusRepository], (_, _) => Result(Some(0), "", 1))
        observer.periodSeconds should equal(300)
      }
    }
  }

  describe("NewScript.validate") {
    it("should accept a valid script and reject bad intervals and timeouts") {
      Future {
        NewScript.validate(NewScript("backup", "true", 60, 30)) shouldBe None
        NewScript.validate(NewScript(" ", "true", 60, 30)) shouldBe defined
        NewScript.validate(NewScript("backup", "  ", 60, 30)) shouldBe defined
        NewScript.validate(NewScript("backup", "true", 5, 5)) shouldBe defined
        NewScript.validate(NewScript("backup", "true", 60, 0)) shouldBe defined
        NewScript.validate(NewScript("backup", "true", 60, 61)) shouldBe defined
      }
    }
  }
}
