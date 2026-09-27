package com.danielsanrocha.xatu.commons

import com.danielsanrocha.xatu.models.internals.ContainerMetrics
import com.typesafe.scalalogging.Logger
import scalaj.http.{Http, HttpOptions}

import java.time.Instant
import scala.util.Try

/** Parsers for the docker engine api json. Pure functions, tested with real responses. */
object ContainerStats {

  /** A running container from GET /containers/json. */
  case class RunningContainer(id: String, name: String, image: String)

  /** Cumulative counters from GET /containers/{id}/stats, rates come from two of them. */
  case class Counters(
      at: Long,
      cpuTotal: Long,
      systemCpu: Long,
      onlineCpus: Int,
      memUsage: Long,
      memLimit: Long,
      netRx: Long,
      netTx: Long,
      blockRead: Long,
      blockWrite: Long,
      pids: Int
  )

  /** Details from GET /containers/{id}/json. */
  case class Details(startedAt: Option[Long], restartCount: Int, oomKilled: Boolean, health: Option[String], cpuLimitCores: Double)

  private def long(v: ujson.Value): Long = Try(v.num.toLong).getOrElse(0L)
  private def opt(v: ujson.Value, path: String*): Option[ujson.Value] =
    path.foldLeft(Option(v)) { (acc, key) => acc.flatMap(_.objOpt).flatMap(_.get(key)).filterNot(_.isNull) }

  def parseList(json: String): Seq[RunningContainer] =
    ujson.read(json).arr.toSeq.map { c =>
      val name = c("Names").arr.headOption.map(_.str.stripPrefix("/")).getOrElse(c("Id").str.take(12))
      RunningContainer(c("Id").str, name, c("Image").str)
    }

  def parseStats(json: String, at: Long): Counters = {
    val s = ujson.read(json)
    val mem = opt(s, "memory_stats")
    // like `docker stats`: the page cache that can be reclaimed is not usage (cgroup v2 / v1 keys)
    val inactiveFile = mem
      .flatMap(m => opt(m, "stats", "inactive_file").orElse(opt(m, "stats", "total_inactive_file")))
      .map(long)
      .getOrElse(0L)
    val usage = mem.flatMap(opt(_, "usage")).map(long).getOrElse(0L)
    val networks = opt(s, "networks").map(_.obj.values.toSeq).getOrElse(Seq())
    val blkio = opt(s, "blkio_stats", "io_service_bytes_recursive").map(_.arr.toSeq).getOrElse(Seq())
    def blk(op: String) = blkio.filter(e => opt(e, "op").exists(_.str.equalsIgnoreCase(op))).map(e => long(e("value"))).sum

    Counters(
      at = at,
      cpuTotal = opt(s, "cpu_stats", "cpu_usage", "total_usage").map(long).getOrElse(0L),
      systemCpu = opt(s, "cpu_stats", "system_cpu_usage").map(long).getOrElse(0L),
      onlineCpus = opt(s, "cpu_stats", "online_cpus").map(long).getOrElse(1L).toInt,
      memUsage = math.max(0L, usage - inactiveFile),
      memLimit = mem.flatMap(opt(_, "limit")).map(long).getOrElse(0L),
      netRx = networks.map(n => long(n("rx_bytes"))).sum,
      netTx = networks.map(n => long(n("tx_bytes"))).sum,
      blockRead = blk("read"),
      blockWrite = blk("write"),
      pids = opt(s, "pids_stats", "current").map(long).getOrElse(0L).toInt
    )
  }

  def parseDetails(json: String): Details = {
    val d = ujson.read(json)
    Details(
      startedAt = opt(d, "State", "StartedAt").flatMap(v => Try(Instant.parse(v.str).toEpochMilli).toOption).filter(_ > 0),
      restartCount = opt(d, "RestartCount").map(long).getOrElse(0L).toInt,
      oomKilled = opt(d, "State", "OOMKilled").exists(_.bool),
      health = opt(d, "State", "Health", "Status").map(_.str),
      cpuLimitCores = opt(d, "HostConfig", "NanoCpus").map(long).getOrElse(0L) / 1e9
    )
  }

  /** CPU usage between two readings, 100 = one full core (the `docker stats` formula). */
  def cpuPercent(previous: Counters, current: Counters): Double = {
    val cpu = current.cpuTotal - previous.cpuTotal
    val system = current.systemCpu - previous.systemCpu
    if (cpu <= 0 || system <= 0) 0.0 else cpu.toDouble / system * current.onlineCpus * 100
  }

  def metrics(server: String, c: RunningContainer, previous: Counters, current: Counters, details: Details): ContainerMetrics = {
    val seconds = (current.at - previous.at) / 1000.0
    def rate(p: Long, n: Long) = SystemMetrics.rate(p, n, seconds)
    ContainerMetrics(
      server = server,
      containerName = c.name,
      containerId = c.id,
      image = c.image,
      collectedAt = current.at,
      startedAt = details.startedAt,
      restartCount = details.restartCount,
      oomKilled = details.oomKilled,
      health = details.health,
      cpuPercent = cpuPercent(previous, current),
      cpuLimitCores = details.cpuLimitCores,
      memUsageBytes = current.memUsage,
      memLimitBytes = current.memLimit,
      netRxBytesPerSec = rate(previous.netRx, current.netRx),
      netTxBytesPerSec = rate(previous.netTx, current.netTx),
      blockReadBytesPerSec = rate(previous.blockRead, current.blockRead),
      blockWriteBytesPerSec = rate(previous.blockWrite, current.blockWrite),
      pids = current.pids
    )
  }
}

/**
 * Samples every running container through the docker engine http api (DOCKER_HOST
 * tcp://...). Rates need two readings of the same container id, so a new or recreated
 * container shows up from its second sample on.
 */
class ContainerStatsReader(server: String, dockerUrl: String) {
  import ContainerStats._

  private val logging: Logger = Logger(this.getClass)
  private var previous: Map[String, Counters] = Map.empty

  private def get(path: String): String = {
    val response = Http(s"$dockerUrl$path").option(HttpOptions.connTimeout(5000)).option(HttpOptions.readTimeout(10000)).asString
    if (response.code != 200) throw new Exception(s"Docker returned ${response.code} for $path")
    response.body
  }

  def sample(): Seq[ContainerMetrics] = synchronized {
    val running = parseList(get("/containers/json"))
    val current = running.flatMap { c =>
      Try((c, parseStats(get(s"/containers/${c.id}/stats?stream=false&one-shot=true"), System.currentTimeMillis()))).fold(
        e => { logging.warn(s"Could not read stats of container ${c.name}: ${e.getMessage}"); None },
        Some(_)
      )
    }

    val result = current.flatMap { case (c, counters) =>
      previous.get(c.id).flatMap { prev =>
        Try(metrics(server, c, prev, counters, parseDetails(get(s"/containers/${c.id}/json")))).fold(
          e => { logging.warn(s"Could not read details of container ${c.name}: ${e.getMessage}"); None },
          Some(_)
        )
      }
    }

    // only the containers still running are kept, so removed ones do not accumulate
    previous = current.map { case (c, counters) => c.id -> counters }.toMap
    logging.debug(s"${running.size} running containers, ${result.size} samples")
    result
  }
}

object ContainerStatsReader {

  /**
   * http url of the docker engine from a DOCKER_HOST like tcp://127.0.0.1:2375. None when
   * docker is reached by its unix socket (no DOCKER_HOST), not supported by the http client.
   */
  def dockerUrl(dockerHost: Option[String]): Option[String] =
    dockerHost.map(_.trim).filter(_.nonEmpty).collect {
      case h if h.startsWith("tcp://") => "http://" + h.stripPrefix("tcp://").stripSuffix("/")
      case h if h.startsWith("http")   => h.stripSuffix("/")
    }
}
