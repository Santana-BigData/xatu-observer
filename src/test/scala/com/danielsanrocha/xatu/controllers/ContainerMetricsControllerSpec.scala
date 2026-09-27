package com.danielsanrocha.xatu.controllers

import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.models.internals.{ContainerLocation, ContainerMetrics}
import com.danielsanrocha.xatu.repositories.ContainerMetricsRepository
import com.twitter.finagle.http.Status
import org.mockito.ArgumentMatchers.{anyLong, anyString}
import org.mockito.Mockito.{never, verify, when}
import org.scalatestplus.mockito.MockitoSugar.mock

import scala.concurrent.Future

class ContainerMetricsControllerSpec extends UnitSpec with TestController {
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

  private val sample = ContainerMetrics("wl3-big-server-4", "yeshua-2-app-main", "abc", "node:20", 60000, Some(1000), 2, oomKilled = false, Some("healthy"),
    150.5, 2.0, 700, 2048, 10, 20, 30, 40, 5)

  describe("GET /api/metrics/containers") {
    it("should return where each container is running") {
      implicit val repository: ContainerMetricsRepository = mock[ContainerMetricsRepository]
      when(repository.inventory()).thenReturn(Future.successful(Seq(ContainerLocation("yeshua-2-app-main", "wl3-big-server-4", "abc", "node:20", Some(1000), 60000))))
      val server = createServer(new ContainerMetricsController())

      Future {
        val hit = ujson.read(server.httpGet("/api/metrics/containers", andExpect = Status.Ok).contentString)("hits")(0)
        (hit("container_name").str, hit("server").str, hit("container_id").str) should equal(("yeshua-2-app-main", "wl3-big-server-4", "abc"))
      }
    }
  }

  describe("GET /api/metrics/containers/samples") {
    it("should return the samples of a container by name in snake_case") {
      implicit val repository: ContainerMetricsRepository = mock[ContainerMetricsRepository]
      when(repository.byName("yeshua-2-app-main", 0, 600000)).thenReturn(Future.successful(Seq(sample)))
      val server = createServer(new ContainerMetricsController())

      Future {
        val hit = ujson.read(server.httpGet("/api/metrics/containers/samples?name=yeshua-2-app-main&from=0&to=600000", andExpect = Status.Ok).contentString)("hits")(0)
        hit("cpu_percent").num should equal(150.5)
        hit("mem_usage_bytes").num should equal(700)
        hit("block_write_bytes_per_sec").num should equal(40)
        hit("health").str should equal("healthy")
      }
    }

    it("should reject missing or double filters and long ranges without querying") {
      implicit val repository: ContainerMetricsRepository = mock[ContainerMetricsRepository]
      val server = createServer(new ContainerMetricsController())

      Future {
        server.httpGet("/api/metrics/containers/samples", andExpect = Status.BadRequest)
        server.httpGet("/api/metrics/containers/samples?name=a&server=b", andExpect = Status.BadRequest)
        server.httpGet(s"/api/metrics/containers/samples?name=a&from=0&to=${9L * 24 * 3600 * 1000}", andExpect = Status.BadRequest)
        verify(repository, never()).byName(anyString(), anyLong(), anyLong())
        verify(repository, never()).byServer(anyString(), anyLong(), anyLong())
        succeed
      }
    }
  }
}
