package com.danielsanrocha.xatu

import com.danielsanrocha.xatu.models.internals.ServerCheck
import com.danielsanrocha.xatu.repositories.{StatusRepository, StatusRepositoryImpl}
import org.mockito.ArgumentMatchers.{anyLong, anyString}
import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar.mock

import scala.concurrent.Future

class ClusterSpec extends UnitSpec {
  describe("LeaderElection") {
    it("should follow the redis lock") {
      val repository = mock[StatusRepository]
      val election = new LeaderElection("s1", repository, 10) {
        def tick(): Unit = run()
      }

      Future {
        election.isLeader should equal(false)

        when(repository.acquireLeadership(anyString(), anyLong())).thenReturn(true)
        election.tick()
        election.isLeader should equal(true)

        // another server took it (lock expired while this one was stuck)
        when(repository.acquireLeadership(anyString(), anyLong())).thenReturn(false)
        election.tick()
        election.isLeader should equal(false)
      }
    }
  }

  describe("StatusRepositoryImpl") {
    it("should use :: separated keys") {
      Future {
        StatusRepositoryImpl.statusKey("service", 34, "wl3-big-server-4") should equal("xatu::status::service::34::wl3-big-server-4")
        StatusRepositoryImpl.KnownServers should equal("xatu::servers::known")
        StatusRepositoryImpl.Leader should equal("xatu::leader")
      }
    }

    it("should keep every field of a check in json") {
      Future {
        val withMessage = ServerCheck("api", 3, "s1", 'F', Some("HTTP 502 \"bad\""), 1790000000000L)
        val without = withMessage.copy(status = 'W', message = None)
        StatusRepositoryImpl.fromJson(StatusRepositoryImpl.toJson(withMessage)) should equal(withMessage)
        StatusRepositoryImpl.fromJson(StatusRepositoryImpl.toJson(without)) should equal(without)
      }
    }
  }
}
