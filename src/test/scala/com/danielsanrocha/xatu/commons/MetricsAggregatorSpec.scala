package com.danielsanrocha.xatu.commons

import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.models.internals.ServerMetrics

import scala.concurrent.Future

class MetricsAggregatorSpec extends UnitSpec {
  private def sample(at: Long, cpu: Double, memUsed: Long, rx: Double) =
    ServerMetrics("s1", at, cpu, 16, 1, 2, 3, 1000, memUsed, 1000 - memUsed, 0, 0, 500, 100, rx, 0)

  describe("aggregate") {
    it("should average samples in buckets stamped with the bucket start") {
      Future {
        val samples = Seq(
          sample(0, 10, 100, 1000),
          sample(60000, 30, 300, 3000),
          sample(300000, 50, 500, 5000),
          sample(360000, 70, 700, 7000)
        )
        val result = MetricsAggregator.aggregate(samples, 300)

        result.map(_.collectedAt) should equal(Seq(0L, 300000L))
        result.map(_.cpuPercent) should equal(Seq(20.0, 60.0))
        result.map(_.memUsedBytes) should equal(Seq(200L, 600L))
        result.map(_.netRxBytesPerSec) should equal(Seq(2000.0, 6000.0))
      }
    }

    it("should keep samples as they are when step equals the interval") {
      Future {
        val samples = Seq(sample(0, 10, 100, 1), sample(60000, 20, 200, 2))
        MetricsAggregator.aggregate(samples, 60) should equal(samples)
      }
    }
  }

  describe("defaultStep") {
    it("should pick a step with at most ~720 points") {
      Future {
        val hour = 3600L * 1000
        MetricsAggregator.defaultStep(0, hour, 60) should equal(60)
        MetricsAggregator.defaultStep(0, 24 * hour, 60) should equal(120)
        MetricsAggregator.defaultStep(0, 7 * 24 * hour, 60) should equal(900)
        MetricsAggregator.defaultStep(0, hour, 300) should equal(300)
      }
    }
  }
}
