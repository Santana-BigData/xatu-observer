package com.danielsanrocha.xatu.commons

import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.commons.SystemMetrics._

import java.nio.file.{Files, Path}
import scala.concurrent.Future

class SystemMetricsSpec extends UnitSpec {
  private val stat =
    """cpu  100 10 50 800 40 0 0 0 5 0
      |cpu0 50 5 25 400 20 0 0 0 0 0
      |cpu1 50 5 25 400 20 0 0 0 0 0
      |intr 12345
      |ctxt 999
      |""".stripMargin

  private val meminfo =
    """MemTotal:       16000000 kB
      |MemFree:         2000000 kB
      |MemAvailable:    6000000 kB
      |SwapTotal:       1000000 kB
      |SwapFree:         250000 kB
      |HugePages_Total:       0
      |""".stripMargin

  private def netdev(ethRx: Long, ethTx: Long) =
    s"""Inter-|   Receive                                                |  Transmit
       | face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
       |    lo: 5000 10 0 0 0 0 0 0 5000 10 0 0 0 0 0 0
       |  eno1: $ethRx 100 0 0 0 0 0 0 $ethTx 90 0 0 0 0 0 0
       |docker0: 7000 10 0 0 0 0 0 0 9000 10 0 0 0 0 0 0
       |""".stripMargin

  describe("parsers") {
    it("should read cpu times ignoring guest columns") {
      Future {
        parseCpu(stat) should equal(CpuTimes(total = 1000, idle = 840))
        parseCpuCores(stat) should equal(2)
      }
    }

    it("should compute cpu usage between two readings") {
      Future {
        cpuPercent(CpuTimes(1000, 840), CpuTimes(2000, 1590)) should equal(25.0)
        cpuPercent(CpuTimes(1000, 840), CpuTimes(1000, 840)) should equal(0.0)
      }
    }

    it("should read meminfo in bytes") {
      Future {
        val mem = parseMemInfo(meminfo)
        mem("MemTotal") should equal(16000000L * 1024)
        mem("MemAvailable") should equal(6000000L * 1024)
        mem("HugePages_Total") should equal(0L)
      }
    }

    it("should read load average") {
      Future { parseLoadAvg("3.40 5.83 5.36 3/3035 2848867\n") should equal((3.40, 5.83, 5.36)) }
    }

    it("should read rx and tx bytes of each interface") {
      Future {
        val net = parseNetDev(netdev(1000, 2000))
        net.keySet should equal(Set("lo", "eno1", "docker0"))
        net("eno1") should equal(NetCounters(1000, 2000))
      }
    }

    it("should treat counter reset as zero rate") {
      Future {
        rate(1000, 7000, 60) should equal(100.0)
        rate(7000, 1000, 60) should equal(0.0)
      }
    }
  }

  describe("SystemMetricsReader") {
    def fakeHost(): (Path, Path) = {
      val proc = Files.createTempDirectory("proc")
      Files.createDirectories(proc.resolve("net"))
      Files.writeString(proc.resolve("stat"), stat)
      Files.writeString(proc.resolve("meminfo"), meminfo)
      Files.writeString(proc.resolve("loadavg"), "1.00 2.00 3.00 1/100 42\n")
      Files.writeString(proc.resolve("net/dev"), netdev(0, 0))

      // only eno1 is a physical interface (has a device link)
      val sys = Files.createTempDirectory("sysnet")
      Files.createDirectories(sys.resolve("eno1/device"))
      Files.createDirectories(sys.resolve("lo"))
      Files.createDirectories(sys.resolve("docker0"))
      (proc, sys)
    }

    it("should return nothing on the first sample and metrics on the next ones") {
      val (proc, sys) = fakeHost()
      val reader = new SystemMetricsReader("test-server", Seq(), "/", proc, sys)

      Future {
        reader.sample() shouldBe None

        Thread.sleep(1100)
        Files.writeString(proc.resolve("stat"), stat.replace("cpu  100 10 50 800 40", "cpu  400 10 150 1300 40"))
        Files.writeString(proc.resolve("net/dev"), netdev(1100000, 2200000))

        val m = reader.sample().get
        m.server should equal("test-server")
        m.cpuCores should equal(2)
        m.cpuPercent should equal(400.0 * 100 / 900)
        m.load1m should equal(1.0)
        m.memTotalBytes should equal(16000000L * 1024)
        m.memUsedBytes should equal(10000000L * 1024)
        m.swapUsedBytes should equal(750000L * 1024)
        m.diskTotalBytes should be > 0L
        // only eno1 counts: ~1MB/s received in ~1.1s, docker0 and lo are ignored
        m.netRxBytesPerSec should (be > 0.0 and be <= 1100000.0 / 1.1)
        m.netTxBytesPerSec should equal(2 * m.netRxBytesPerSec +- 1.0)
      }
    }

    it("should use only the configured interfaces when given") {
      val (proc, sys) = fakeHost()
      val reader = new SystemMetricsReader("test-server", Seq("docker0"), "/", proc, sys)

      Future {
        reader.sample()
        Thread.sleep(1100)
        Files.writeString(proc.resolve("net/dev"), netdev(1100000, 2200000))
        val m = reader.sample().get
        // docker0 counters did not change
        m.netRxBytesPerSec should equal(0.0)
        m.netTxBytesPerSec should equal(0.0)
      }
    }
  }
}
