package com.danielsanrocha.xatu.commons

import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.commons.ContainerStats._

import scala.concurrent.Future
import scala.io.Source

class ContainerStatsSpec extends UnitSpec {
  // real responses of docker 29 (api 1.55, cgroup v2); list and inspect trimmed to the fields used
  private def fixture(name: String) = Source.fromResource(s"docker/$name").mkString

  describe("parsers") {
    it("should read name without the slash, id and image of the running containers") {
      Future {
        parseList(fixture("list.json")).map(_.name) should equal(
          Seq("yeshua-2-clickword-main", "yeshua-2-n8nready-api-supabase-functions", "yeshua-483-n8n-pedropedro-main")
        )
        parseList(fixture("list.json")).last.image should equal("n8nio/n8n:latest")
      }
    }

    it("should read the counters of stats, memory without the reclaimable page cache") {
      Future {
        val c = parseStats(fixture("stats-2.json"), 1000)
        c.onlineCpus should equal(24)
        c.memUsage should equal(404041728L) // usage - inactive_file, as `docker stats`
        c.memLimit should equal(1073741824L)
        (c.netRx, c.netTx) should equal((2959765L, 970106L))
        c.blockWrite should be > 0L
        c.pids should be > 0
      }
    }

    it("should read restart count, start, limits and health of inspect") {
      Future {
        val d = parseDetails(fixture("inspect.json"))
        d.restartCount should equal(0)
        d.oomKilled should equal(false)
        d.health should equal(None)
        d.cpuLimitCores should equal(2.0)
        d.startedAt should equal(Some(java.time.Instant.parse("2026-09-25T21:27:13.348706806Z").toEpochMilli))
      }
    }

    it("should tolerate containers without networks, blkio or memory stats (host network, stopped cgroup)") {
      Future {
        val c = parseStats("""{"cpu_stats": {"cpu_usage": {"total_usage": 10}, "system_cpu_usage": 100, "online_cpus": 2}, "memory_stats": {}}""", 0)
        (c.memUsage, c.netRx, c.blockRead, c.pids) should equal((0L, 0L, 0L, 0))
      }
    }
  }

  describe("metrics") {
    it("should compute cpu like docker stats between two real readings") {
      Future {
        val prev = parseStats(fixture("stats-1.json"), 0)
        val cur = parseStats(fixture("stats-2.json"), 3000)
        cpuPercent(prev, cur) should equal(0.2406 +- 0.0001)
      }
    }

    it("should build a sample with rates per second") {
      Future {
        val prev = Counters(0, 1000, 10000, 4, 0, 0, netRx = 1000, netTx = 0, blockRead = 0, blockWrite = 500, pids = 3)
        val cur = Counters(60000, 3000, 14000, 4, 700, 2048, netRx = 61000, netTx = 6000, blockRead = 0, blockWrite = 500, pids = 5)
        val m = metrics("wl3-big-server-4", RunningContainer("abc", "yeshua-2-app-main", "node:20"), prev, cur, parseDetails(fixture("inspect.json")))
        m.cpuPercent should equal(200.0) // 2000/4000 of the host * 4 cpus = two full cores
        m.netRxBytesPerSec should equal(1000.0)
        m.netTxBytesPerSec should equal(100.0)
        m.blockWriteBytesPerSec should equal(0.0)
        (m.server, m.containerName, m.containerId, m.memUsageBytes, m.pids) should equal(("wl3-big-server-4", "yeshua-2-app-main", "abc", 700L, 5))
      }
    }

    it("should treat restarted counters as zero, not negative") {
      Future {
        val prev = Counters(0, 5000, 10000, 4, 0, 0, 9000, 9000, 9000, 9000, 1)
        val cur = Counters(60000, 100, 14000, 4, 0, 0, 10, 10, 10, 10, 1)
        val m = metrics("s", RunningContainer("abc", "n", "i"), prev, cur, parseDetails(fixture("inspect.json")))
        (m.cpuPercent, m.netRxBytesPerSec, m.blockReadBytesPerSec) should equal((0.0, 0.0, 0.0))
      }
    }
  }

  describe("dockerUrl") {
    it("should use tcp DOCKER_HOST and skip the unix socket") {
      Future {
        ContainerStatsReader.dockerUrl(Some("tcp://127.0.0.1:2375")) should equal(Some("http://127.0.0.1:2375"))
        ContainerStatsReader.dockerUrl(Some("unix:///var/run/docker.sock")) should equal(None)
        ContainerStatsReader.dockerUrl(None) should equal(None)
      }
    }
  }
}
