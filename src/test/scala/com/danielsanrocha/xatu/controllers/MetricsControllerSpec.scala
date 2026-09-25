package com.danielsanrocha.xatu.controllers

import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.models.internals.{MetricsServer, ServerMetrics}
import com.danielsanrocha.xatu.repositories.MetricsRepository
import com.twitter.finagle.http.Status
import org.mockito.ArgumentMatchers.{anyLong, anyString}
import org.mockito.Mockito.{never, verify, when}
import org.scalatestplus.mockito.MockitoSugar.mock

import scala.concurrent.Future

class MetricsControllerSpec extends UnitSpec with TestController {
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

  private def sample(at: Long, cpu: Double) =
    ServerMetrics("wl3-big-server-1", at, cpu, 16, 1, 2, 3, 1000, 400, 600, 0, 0, 500, 100, 10, 20)

  describe("GET /api/metrics/servers") {
    it("should return the servers with metrics") {
      implicit val repository: MetricsRepository = mock[MetricsRepository]
      when(repository.servers()).thenReturn(Future.successful(Seq(MetricsServer("wl3-big-server-1", 1000, 16, 2000))))

      val server = createServer(new MetricsController(60))

      Future {
        val response = server.httpGet("/api/metrics/servers", andExpect = Status.Ok)
        val json = ujson.read(response.contentString)
        json("count").num should equal(1)
        json("hits")(0)("server").str should equal("wl3-big-server-1")
        json("hits")(0)("cpu_cores").num should equal(16)
        json("hits")(0)("mem_total_bytes").num should equal(2000)
      }
    }
  }

  describe("GET /api/metrics") {
    it("should return the samples aggregated by step, in snake_case") {
      implicit val repository: MetricsRepository = mock[MetricsRepository]
      when(repository.query("wl3-big-server-1", 0, 600000))
        .thenReturn(Future.successful(Seq(sample(0, 10), sample(60000, 30), sample(300000, 50))))

      val server = createServer(new MetricsController(60))

      Future {
        val response = server.httpGet("/api/metrics?server=wl3-big-server-1&from=0&to=600000&step=300", andExpect = Status.Ok)
        val json = ujson.read(response.contentString)
        json("step").num should equal(300)
        json("points").arr.map(_("collected_at").num) should equal(Seq(0, 300000))
        json("points").arr.map(_("cpu_percent").num) should equal(Seq(20, 50))
        json("points")(0)("net_rx_bytes_per_sec").num should equal(10)
        json("points")(0)("load_1m").num should equal(1)
        json("points")(0)("load_15m").num should equal(3)
      }
    }

    it("should use a default step when none is given") {
      implicit val repository: MetricsRepository = mock[MetricsRepository]
      when(repository.query(anyString(), anyLong(), anyLong())).thenReturn(Future.successful(Seq()))

      val server = createServer(new MetricsController(60))

      Future {
        val day = 24L * 3600 * 1000
        val response = server.httpGet(s"/api/metrics?server=s1&from=0&to=${7 * day}", andExpect = Status.Ok)
        ujson.read(response.contentString)("step").num should equal(900)
      }
    }

    it("should reject ranges longer than 8 days without querying cassandra") {
      implicit val repository: MetricsRepository = mock[MetricsRepository]
      val server = createServer(new MetricsController(60))

      Future {
        val day = 24L * 3600 * 1000
        server.httpGet(s"/api/metrics?server=s1&from=0&to=${9 * day}", andExpect = Status.BadRequest)
        server.httpGet("/api/metrics?server=s1&from=10&to=5", andExpect = Status.BadRequest)
        server.httpGet("/api/metrics?server=s1&step=0", andExpect = Status.BadRequest)
        verify(repository, never()).query(anyString(), anyLong(), anyLong())
        succeed
      }
    }
  }
}
