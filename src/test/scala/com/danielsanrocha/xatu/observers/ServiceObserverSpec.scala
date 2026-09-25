package com.danielsanrocha.xatu.observers

import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.models.internals.{ServerCheck, Service}
import com.danielsanrocha.xatu.repositories.StatusRepository
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{never, times, verify}
import org.scalatestplus.mockito.MockitoSugar.mock

import java.sql.Timestamp
import scala.concurrent.Future

class ServiceObserverSpec extends UnitSpec {
  private val webmail = Service(34, "webmail", "/var/log/webmail", "[0-9a-zA-Z]+\\.log", "", 'W', new Timestamp(0), new Timestamp(0))

  describe("statusFor") {
    it("should report a running service as working, even when disabled") {
      Future {
        ServiceObserver.statusFor("active", "enabled") should equal(Some('W'))
        ServiceObserver.statusFor("active", "disabled") should equal(Some('W'))
      }
    }

    it("should report a stopped service that is expected to run as failed") {
      Future {
        ServiceObserver.statusFor("inactive", "enabled") should equal(Some('F'))
        ServiceObserver.statusFor("failed", "static") should equal(Some('F'))
        ServiceObserver.statusFor("activating", "enabled") should equal(Some('F'))
      }
    }

    it("should not report a stopped service that is disabled, masked or missing on this server") {
      Future {
        ServiceObserver.statusFor("inactive", "disabled") should equal(None)
        ServiceObserver.statusFor("inactive", "masked") should equal(None)
        ServiceObserver.statusFor("inactive", "Failed to get unit file state for webmail.service: No such file or directory") should equal(None)
        ServiceObserver.statusFor("inactive", "") should equal(None)
      }
    }

    it("should not call is-enabled when the service is running") {
      Future {
        ServiceObserver.statusFor("active", throw new Exception("should not run")) should equal(Some('W'))
      }
    }
  }

  describe("task") {
    class FakeSystemctl(repository: StatusRepository, answers: Map[String, String]) extends ServiceObserver(webmail, "wl3-big-server-4", repository) {
      override protected def systemctl(args: String*): String = answers(args.head)
    }

    it("should save the status of this server when the service is expected to run") {
      val repository = mock[StatusRepository]
      new FakeSystemctl(repository, Map("is-active" -> "failed", "is-enabled" -> "enabled")).task.run()

      Future {
        val saved: ArgumentCaptor[ServerCheck] = ArgumentCaptor.forClass(classOf[ServerCheck])
        verify(repository, times(1)).save(saved.capture())
        val check = saved.getValue
        (check.kind, check.targetId, check.server, check.status, check.message) should equal(("service", 34L, "wl3-big-server-4", 'F', Some("failed")))
      }
    }

    it("should not save anything when the service is disabled on this server") {
      val repository = mock[StatusRepository]
      new FakeSystemctl(repository, Map("is-active" -> "inactive", "is-enabled" -> "disabled")).task.run()

      Future {
        verify(repository, never()).save(any())
        succeed
      }
    }
  }
}
