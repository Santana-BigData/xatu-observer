package com.danielsanrocha.xatu.commons

import com.danielsanrocha.xatu.models.internals.ServerMetrics
import com.typesafe.scalalogging.Logger

import java.nio.file.{Files, Path, Paths}
import scala.util.Try

/** Parsers for the linux /proc files. Pure functions, so they are tested with fixtures. */
object SystemMetrics {

  /** Cumulative cpu jiffies from the first line of /proc/stat. */
  case class CpuTimes(total: Long, idle: Long)

  /** Cumulative bytes of one network interface from /proc/net/dev. */
  case class NetCounters(rx: Long, tx: Long)

  def parseCpu(stat: String): CpuTimes = {
    val fields = stat.linesIterator.find(_.startsWith("cpu ")).get.trim.split("\\s+").tail.map(_.toLong)
    // user nice system idle iowait irq softirq steal (guest and guest_nice are already in user/nice)
    val times = fields.take(8).padTo(8, 0L)
    CpuTimes(total = times.sum, idle = times(3) + times(4))
  }

  def parseCpuCores(stat: String): Int =
    stat.linesIterator.count(l => l.startsWith("cpu") && l.length > 3 && l.charAt(3).isDigit)

  def cpuPercent(previous: CpuTimes, current: CpuTimes): Double = {
    val total = current.total - previous.total
    val idle = current.idle - previous.idle
    if (total <= 0) 0.0 else math.max(0.0, math.min(100.0, (total - idle) * 100.0 / total))
  }

  /** /proc/meminfo values in bytes (the file reports kB). */
  def parseMemInfo(meminfo: String): Map[String, Long] =
    meminfo.linesIterator.flatMap { line =>
      line.split(":", 2) match {
        case Array(key, value) =>
          value.trim.split("\\s+").toList match {
            case n :: "kB" :: Nil => Some(key.trim -> n.toLong * 1024)
            case n :: Nil         => Try(key.trim -> n.toLong).toOption
            case _                => None
          }
        case _ => None
      }
    }.toMap

  def parseLoadAvg(loadavg: String): (Double, Double, Double) = {
    val f = loadavg.trim.split("\\s+")
    (f(0).toDouble, f(1).toDouble, f(2).toDouble)
  }

  def parseNetDev(netdev: String): Map[String, NetCounters] =
    netdev.linesIterator
      .drop(2)
      .flatMap { line =>
        line.split(":", 2) match {
          case Array(iface, values) =>
            val f = values.trim.split("\\s+")
            if (f.length >= 9) Some(iface.trim -> NetCounters(f(0).toLong, f(8).toLong)) else None
          case _ => None
        }
      }
      .toMap

  /** Bytes per second between two readings. A counter reset (reboot, interface recreated) counts as 0. */
  def rate(previous: Long, current: Long, seconds: Double): Double =
    if (seconds <= 0 || current < previous) 0.0 else (current - previous) / seconds
}

/**
 * Samples cpu, memory, load, disk and network of the host. CPU and network are
 * rates, so the first call only records the counters and returns None.
 */
class SystemMetricsReader(
    server: String,
    netInterfaces: Seq[String],
    diskPath: String,
    procDir: Path = Paths.get("/proc"),
    sysNetDir: Path = Paths.get("/sys/class/net")
) {
  import SystemMetrics._

  private val logging: Logger = Logger(this.getClass)

  private case class Counters(at: Long, cpu: CpuTimes, net: NetCounters)
  private var previous: Option[Counters] = None

  private def read(file: String): String = new String(Files.readAllBytes(procDir.resolve(file)))

  /** Physical interfaces have a device link in sysfs; lo, docker bridges, veth and vpn tunnels do not. */
  private def isPhysical(iface: String): Boolean = Files.exists(sysNetDir.resolve(iface).resolve("device"))

  private def selectedNet(all: Map[String, NetCounters]): NetCounters = {
    val selected =
      if (netInterfaces.nonEmpty) all.filter { case (iface, _) => netInterfaces.contains(iface) }
      else all.filter { case (iface, _) => isPhysical(iface) }
    NetCounters(selected.values.map(_.rx).sum, selected.values.map(_.tx).sum)
  }

  def sample(): Option[ServerMetrics] = synchronized {
    val now = System.currentTimeMillis()
    val stat = read("stat")
    val current = Counters(now, parseCpu(stat), selectedNet(parseNetDev(read("net/dev"))))
    val last = previous
    previous = Some(current)

    last.map { prev =>
      val seconds = (current.at - prev.at) / 1000.0
      val mem = parseMemInfo(read("meminfo"))
      val (load1, load5, load15) = parseLoadAvg(read("loadavg"))
      val store = Files.getFileStore(Paths.get(diskPath))
      val memTotal = mem.getOrElse("MemTotal", 0L)
      val memAvailable = mem.getOrElse("MemAvailable", mem.getOrElse("MemFree", 0L))
      val swapTotal = mem.getOrElse("SwapTotal", 0L)

      val metrics = ServerMetrics(
        server = server,
        collectedAt = now,
        cpuPercent = cpuPercent(prev.cpu, current.cpu),
        cpuCores = parseCpuCores(stat),
        load1m = load1,
        load5m = load5,
        load15m = load15,
        memTotalBytes = memTotal,
        memUsedBytes = memTotal - memAvailable,
        memAvailableBytes = memAvailable,
        swapTotalBytes = swapTotal,
        swapUsedBytes = swapTotal - mem.getOrElse("SwapFree", swapTotal),
        diskTotalBytes = store.getTotalSpace,
        diskUsedBytes = store.getTotalSpace - store.getUnallocatedSpace,
        netRxBytesPerSec = rate(prev.net.rx, current.net.rx, seconds),
        netTxBytesPerSec = rate(prev.net.tx, current.net.tx, seconds)
      )
      logging.debug(s"Metrics sample: $metrics")
      metrics
    }
  }
}
