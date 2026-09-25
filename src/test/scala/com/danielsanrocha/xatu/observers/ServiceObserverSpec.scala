package com.danielsanrocha.xatu.observers

import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.models.internals.Service
import com.danielsanrocha.xatu.services.ServiceService
import org.mockito.ArgumentMatchers.{any, anyChar, anyLong}
import org.mockito.Mockito.{never, times, verify, when}
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
    class FakeSystemctl(service: ServiceService, answers: Map[String, String]) extends ServiceObserver(webmail, service) {
      override protected def systemctl(args: String*): String = answers(args.head)
    }

    it("should save the status when the service is expected to run") {
      val service = mock[ServiceService]
      when(service.setStatus(anyLong(), anyChar())).thenReturn(Future.successful(1))
      new FakeSystemctl(service, Map("is-active" -> "inactive", "is-enabled" -> "enabled")).task.run()

      Future {
        verify(service, times(1)).setStatus(34, 'F')
        succeed
      }
    }

    it("should not save anything when the service is disabled on this server") {
      val service = mock[ServiceService]
      new FakeSystemctl(service, Map("is-active" -> "inactive", "is-enabled" -> "disabled")).task.run()

      Future {
        verify(service, never()).setStatus(anyLong(), anyChar())
        succeed
      }
    }
  }
}
