package com.danielsanrocha.xatu.controllers

import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.models.internals.{ContainerLocation, ContainerMetrics}
import com.danielsanrocha.xatu.repositories.ContainerMetricsRepository
import com.twitter.finagle.http.Status
import org.mockito.ArgumentMatchers.{anyLong, anyString}
import org.mockito.Mockito.{never, times, verify, when}
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
      val server = createServer(new ContainerMetricsController(60))

      Future {
        val hit = ujson.read(server.httpGet("/api/metrics/containers", andExpect = Status.Ok).contentString)("hits")(0)
        (hit("container_name").str, hit("server").str, hit("container_id").str) should equal(("yeshua-2-app-main", "wl3-big-server-4", "abc"))
      }
    }
  }

  describe("GET /api/metrics/containers/overview") {
    it("should join the inventory with the latest sample of each container, per server") {
      implicit val repository: ContainerMetricsRepository = mock[ContainerMetricsRepository]
      when(repository.inventory()).thenReturn(
        Future.successful(
          Seq(
            ContainerLocation("yeshua-2-app-main", "wl3-big-server-4", "abc", "node:20", Some(1000), 60000),
            ContainerLocation("yeshua-9-old-main", "wl3-big-server-4", "def", "nginx", None, 1000)
          )
        )
      )
      when(repository.byServer(anyString(), anyLong(), anyLong()))
        .thenReturn(Future.successful(Seq(sample.copy(collectedAt = 1000, cpuPercent = 1), sample, sample.copy(server = "wl3-big-server-1"))))
      val server = createServer(new ContainerMetricsController(60))

      Future {
        val hits = ujson.read(server.httpGet("/api/metrics/containers/overview", andExpect = Status.Ok).contentString)("hits").arr
        hits(0)("container_name").str should equal("yeshua-2-app-main")
        hits(0)("latest")("cpu_percent").num should equal(150.5) // the newest sample of that server
        hits(1).obj.contains("latest") should equal(false) // no recent sample: field omitted
        verify(repository, times(1)).byServer(anyString(), anyLong(), anyLong()) // one query per server
        succeed
      }
    }
  }

  describe("GET /api/metrics/containers/samples") {
    it("should return the samples of a container by name in snake_case") {
      implicit val repository: ContainerMetricsRepository = mock[ContainerMetricsRepository]
      when(repository.byName("yeshua-2-app-main", 0, 600000)).thenReturn(Future.successful(Seq(sample)))
      val server = createServer(new ContainerMetricsController(60))

      Future {
        val hit = ujson.read(server.httpGet("/api/metrics/containers/samples?name=yeshua-2-app-main&from=0&to=600000", andExpect = Status.Ok).contentString)("hits")(0)
        hit("cpu_percent").num should equal(150.5)
        hit("mem_usage_bytes").num should equal(700)
        hit("block_write_bytes_per_sec").num should equal(40)
        hit("health").str should equal("healthy")
      }
    }

    it("should average by step, one line per server") {
      implicit val repository: ContainerMetricsRepository = mock[ContainerMetricsRepository]
      when(repository.byName("yeshua-2-app-main", 0, 600000)).thenReturn(
        Future.successful(
          Seq(
            sample.copy(collectedAt = 0, cpuPercent = 10),
            sample.copy(collectedAt = 60000, cpuPercent = 30, restartCount = 3),
            sample.copy(collectedAt = 60000, cpuPercent = 90, server = "wl3-big-server-1")
          )
        )
      )
      val server = createServer(new ContainerMetricsController(60))

      Future {
        val json = ujson.read(server.httpGet("/api/metrics/containers/samples?name=yeshua-2-app-main&from=0&to=600000&step=300", andExpect = Status.Ok).contentString)
        val hits = json("hits").arr
        json("count").num should equal(2)
        hits.map(h => (h("server").str, h("cpu_percent").num, h("restart_count").num)) should equal(Seq(("wl3-big-server-1", 90, 2), ("wl3-big-server-4", 20, 3)))
      }
    }

    it("should reject missing or double filters and long ranges without querying") {
      implicit val repository: ContainerMetricsRepository = mock[ContainerMetricsRepository]
      val server = createServer(new ContainerMetricsController(60))

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
