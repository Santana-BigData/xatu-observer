package com.danielsanrocha.xatu.commons

import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.models.internals.GpuProcess

import scala.concurrent.Future
import scala.io.Source

class NvidiaSmiSpec extends UnitSpec {
  // real outputs: two Tesla K80 (driver 470) and one RTX 4000 Ada (driver 615)
  private def fixture(name: String) = Source.fromResource(s"nvidia/$name").mkString
  private val MiB = 1024L * 1024

  describe("parseGpus") {
    it("should read every column of a K80 pair, with fan missing") {
      Future {
        val gpus = NvidiaSmi.parseGpus(fixture("gpu-k80.csv"), "wl3-worker-6", 1000)
        gpus.map(_.gpuIndex) should equal(Seq(0, 1))
        val busy = gpus.head
        busy.uuid should equal("GPU-abbf2b53-a25a-4e9f-92a4-cd2b0e92f8c7")
        busy.name should equal("Tesla K80")
        busy.driverVersion should equal("470.256.02")
        busy.memUsedBytes should equal(2959L * MiB)
        busy.memTotalBytes should equal(11441L * MiB)
        busy.temperatureC should equal(47)
        busy.powerDrawW should equal(57.58)
        busy.powerLimitW should equal(149.0)
        busy.fanPercent should equal(None)
        (busy.smClockMhz, busy.memClockMhz, busy.pstate) should equal((562, 2505, "P0"))
        busy.throttleReasons shouldBe empty
        gpus(1).pstate should equal("P8")
        gpus(1).throttleReasons should equal(Seq("gpu_idle"))
      }
    }

    it("should read an Ada board on a newer driver and attach its processes") {
      Future {
        val apps = NvidiaSmi.parseApps(fixture("apps-ada.csv"))
        apps.map(_._2.name) should equal(Seq("/home/legalminer/.local/lib/ollama/llama-server", "/home/legalminer/.cache/pypoetry/virtualenvs/legalminer-X-pRIkrE-py3.11/bin/python"))
        val gpu = NvidiaSmi.parseGpus(fixture("gpu-ada.csv"), "wl3-worker-8", 1000, apps).head
        gpu.name should equal("NVIDIA RTX 4000 Ada Generation")
        gpu.fanPercent should equal(Some(30))
        gpu.memTotalBytes should equal(20475L * MiB)
        gpu.processes should equal(
          Seq(
            GpuProcess(13085, "/home/legalminer/.local/lib/ollama/llama-server", 15506L * MiB),
            GpuProcess(3115753, "/home/legalminer/.cache/pypoetry/virtualenvs/legalminer-X-pRIkrE-py3.11/bin/python", 1074L * MiB)
          )
        )
      }
    }

    it("should attach processes only to their gpu") {
      Future {
        val apps = NvidiaSmi.parseApps(fixture("apps-k80.csv"))
        val gpus = NvidiaSmi.parseGpus(fixture("gpu-k80.csv"), "s", 0, apps)
        gpus.head.processes.map(_.pid) should equal(Seq(1059517L))
        gpus(1).processes shouldBe empty
      }
    }

    it("should skip malformed lines and tolerate N/A values") {
      Future {
        val csv = "garbage\n0, GPU-x, Some GPU, 1.0, N/A, [Not Supported], 100, 200, 50, [N/A], 250.00, [N/A], 1000, 2000, P2, 0x0000000000000044\n"
        val gpus = NvidiaSmi.parseGpus(csv, "s", 0)
        gpus.size should equal(1)
        (gpus.head.utilizationGpuPercent, gpus.head.powerDrawW, gpus.head.fanPercent) should equal((0, 0.0, None))
        gpus.head.throttleReasons should equal(Seq("sw_power_cap", "hw_thermal_slowdown"))
      }
    }
  }

  describe("throttleReasons") {
    it("should decode the nvml bitmask") {
      Future {
        NvidiaSmi.throttleReasons("0x0000000000000000") shouldBe empty
        NvidiaSmi.throttleReasons("0x0000000000000002") should equal(Seq("applications_clocks_setting"))
        NvidiaSmi.throttleReasons("0x00000000000000A8") should equal(Seq("hw_slowdown", "sw_thermal_slowdown", "hw_power_brake_slowdown"))
        NvidiaSmi.throttleReasons("[N/A]") shouldBe empty
      }
    }
  }
}
