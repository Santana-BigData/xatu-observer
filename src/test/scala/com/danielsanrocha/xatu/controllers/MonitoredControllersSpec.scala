package com.danielsanrocha.xatu.controllers

import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.models.internals.{API, ServerCheck, Service}
import com.danielsanrocha.xatu.repositories.StatusRepository
import com.danielsanrocha.xatu.services.{APIService, ServiceService}
import com.twitter.finagle.http.Status
import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar.mock

import java.sql.Timestamp
import scala.concurrent.Future

class MonitoredControllersSpec extends UnitSpec with TestController {
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

  describe("GET /api/services") {
    it("should return the status of each server and the aggregated one, ignoring the status column") {
      implicit val service: ServiceService = mock[ServiceService]
      implicit val statusRepository: StatusRepository = mock[StatusRepository]
      // the status column in mariadb is not used anymore
      val webmail = Service(34, "webmail", "/var/log/webmail", ".*", "", 'W', new Timestamp(0), new Timestamp(0))
      val nginx = Service(16, "nginx", "/var/log/nginx", ".*", "", 'W', new Timestamp(0), new Timestamp(0))
      when(service.getAll(1000, 0)).thenReturn(Future.successful(Seq(webmail, nginx)))
      when(statusRepository.checks("service")).thenReturn(
        Map(34L -> Seq(ServerCheck("service", 34, "s2", 'F', Some("failed"), 2), ServerCheck("service", 34, "s1", 'W', None, 1)))
      )

      val server = createServer(new ServiceController())

      Future {
        val json = ujson.read(server.httpGet("/api/services?limit=1000&offset=0", andExpect = Status.Ok).contentString)
        val hits = json("hits").arr
        hits(0)("status").str should equal("F")
        hits(0)("servers").arr.map(s => (s("server").str, s("status").str)) should equal(Seq(("s1", "W"), ("s2", "F")))
        hits(0)("servers")(1)("message").str should equal("failed")
        // no server checked nginx: unknown
        hits(1)("status").str should equal("U")
        hits(1)("servers").arr shouldBe empty
      }
    }
  }

  describe("GET /api/apis") {
    it("should fail an api only when most servers fail") {
      implicit val service: APIService = mock[APIService]
      implicit val statusRepository: StatusRepository = mock[StatusRepository]
      val sysjud = API(3, "sysjud", "https://sysjud", 443, "/api", 'F', new Timestamp(0), new Timestamp(0))
      when(service.getAll(1000, 0)).thenReturn(Future.successful(Seq(sysjud)))
      when(statusRepository.checks("api")).thenReturn(
        Map(3L -> Seq(ServerCheck("api", 3, "s1", 'W', None, 1), ServerCheck("api", 3, "s2", 'W', None, 1), ServerCheck("api", 3, "s3", 'F', Some("timeout"), 1)))
      )

      val server = createServer(new APIController())

      Future {
        val json = ujson.read(server.httpGet("/api/apis?limit=1000&offset=0", andExpect = Status.Ok).contentString)
        json("hits")(0)("status").str should equal("W")
        json("hits")(0)("servers").arr.size should equal(3)
      }
    }
  }
}
