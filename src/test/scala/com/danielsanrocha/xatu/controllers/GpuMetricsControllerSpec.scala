package com.danielsanrocha.xatu.controllers

import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.commons.GpuMetricsAggregator
import com.danielsanrocha.xatu.models.internals.{GpuInfo, GpuMetrics, GpuProcess}
import com.danielsanrocha.xatu.repositories.GpuMetricsRepository
import com.twitter.finagle.http.Status
import org.mockito.ArgumentMatchers.{anyLong, anyString}
import org.mockito.Mockito.{never, verify, when}
import org.scalatestplus.mockito.MockitoSugar.mock

import scala.concurrent.Future

class GpuMetricsControllerSpec extends UnitSpec with TestController {
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

  private def sample(at: Long, gpu: Int, util: Int, temp: Int, throttle: Seq[String] = Seq()) =
    GpuMetrics("wl3-worker-6", at, gpu, s"GPU-$gpu", "Tesla K80", "470", util, 10, 3000, 12000, temp, 60.0, 149.0, None, 562, 2505, "P0", throttle,
      Seq(GpuProcess(1, "python", 2000)))

  describe("GpuMetricsAggregator") {
    it("should average per gpu and keep any throttle reason of the bucket") {
      Future {
        val points = GpuMetricsAggregator.aggregate(
          Seq(sample(0, 0, 10, 40), sample(60000, 0, 30, 50, Seq("hw_thermal_slowdown")), sample(0, 1, 90, 70), sample(300000, 0, 50, 60)),
          300
        )
        points.map(p => (p.collectedAt, p.gpuIndex, p.utilizationGpuPercent, p.temperatureC)) should equal(
          Seq((0L, 0, 20, 45), (0L, 1, 90, 70), (300000L, 0, 50, 60))
        )
        points.head.throttleReasons should equal(Seq("hw_thermal_slowdown"))
      }
    }
  }

  describe("GET /api/metrics/gpus") {
    it("should return the inventory") {
      implicit val repository: GpuMetricsRepository = mock[GpuMetricsRepository]
      when(repository.inventory()).thenReturn(Future.successful(Seq(GpuInfo("wl3-worker-6", 0, "GPU-a", "Tesla K80", "470", 12000, 1000))))
      val server = createServer(new GpuMetricsController(60))
      Future {
        val hit = ujson.read(server.httpGet("/api/metrics/gpus", andExpect = Status.Ok).contentString)("hits")(0)
        (hit("server").str, hit("gpu_index").num, hit("name").str) should equal(("wl3-worker-6", 0, "Tesla K80"))
      }
    }
  }

  describe("GET /api/metrics/gpus/samples") {
    it("should return samples in snake_case with processes and fan as null") {
      implicit val repository: GpuMetricsRepository = mock[GpuMetricsRepository]
      when(repository.byServer("wl3-worker-6", 0, 60000)).thenReturn(Future.successful(Seq(sample(0, 0, 10, 40))))
      val server = createServer(new GpuMetricsController(60))
      Future {
        val hit = ujson.read(server.httpGet("/api/metrics/gpus/samples?server=wl3-worker-6&from=0&to=60000&step=60", andExpect = Status.Ok).contentString)("hits")(0)
        hit("utilization_gpu_percent").num should equal(10)
        hit("mem_used_bytes").num should equal(3000)
        hit("power_draw_w").num should equal(60.0)
        hit.obj.contains("fan_percent") should equal(false)
        hit("processes")(0)("name").str should equal("python")
      }
    }

    it("should validate the range without querying") {
      implicit val repository: GpuMetricsRepository = mock[GpuMetricsRepository]
      val server = createServer(new GpuMetricsController(60))
      Future {
        server.httpGet("/api/metrics/gpus/samples?server=s&from=10&to=5", andExpect = Status.BadRequest)
        server.httpGet(s"/api/metrics/gpus/samples?server=s&from=0&to=${9L * 24 * 3600 * 1000}", andExpect = Status.BadRequest)
        verify(repository, never()).byServer(anyString(), anyLong(), anyLong())
        succeed
      }
    }
  }
}
