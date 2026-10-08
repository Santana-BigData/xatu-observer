package com.danielsanrocha.xatu.commons

import com.danielsanrocha.xatu.models.internals.{GpuMetrics, GpuProcess}
import com.typesafe.scalalogging.Logger

import java.io.{BufferedReader, InputStreamReader}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters._
import scala.util.Try

/** Parsers for `nvidia-smi --format=csv,noheader,nounits`. Pure functions, tested with real outputs. */
object NvidiaSmi {

  /** Columns of the gpu query, in this order. Works on drivers 470 (K80) and 615 (Ada). */
  val GpuFields: Seq[String] = Seq(
    "index",
    "uuid",
    "name",
    "driver_version",
    "utilization.gpu",
    "utilization.memory",
    "memory.used",
    "memory.total",
    "temperature.gpu",
    "power.draw",
    "power.limit",
    "fan.speed",
    "clocks.sm",
    "clocks.mem",
    "pstate",
    "clocks_throttle_reasons.active"
  )

  val AppFields: Seq[String] = Seq("gpu_uuid", "pid", "process_name", "used_memory")

  // bits of clocks_throttle_reasons.active (nvml.h)
  private val ThrottleBits: Seq[(Long, String)] = Seq(
    0x1L -> "gpu_idle",
    0x2L -> "applications_clocks_setting",
    0x4L -> "sw_power_cap",
    0x8L -> "hw_slowdown",
    0x10L -> "sync_boost",
    0x20L -> "sw_thermal_slowdown",
    0x40L -> "hw_thermal_slowdown",
    0x80L -> "hw_power_brake_slowdown",
    0x100L -> "display_clock_setting"
  )

  private val MiB = 1024L * 1024

  /** "[N/A]", "N/A" and "[Not Supported]" are missing values. */
  private def value(s: String): Option[String] = Option(s.trim).filter(v => v.nonEmpty && !v.startsWith("[") && v != "N/A")
  private def int(s: String): Int = value(s).flatMap(v => Try(v.toDouble.toInt).toOption).getOrElse(0)
  private def double(s: String): Double = value(s).flatMap(v => Try(v.toDouble).toOption).getOrElse(0.0)

  def throttleReasons(hex: String): Seq[String] = {
    val mask = value(hex).flatMap(v => Try(java.lang.Long.parseLong(v.stripPrefix("0x"), 16)).toOption).getOrElse(0L)
    ThrottleBits.collect { case (bit, name) if (mask & bit) != 0 => name }
  }

  /** Rows of the gpu query. A line that does not have every column is skipped. */
  def parseGpus(csv: String, server: String, at: Long, apps: Seq[(String, GpuProcess)] = Seq()): Seq[GpuMetrics] =
    csv.linesIterator.map(_.trim).filter(_.nonEmpty).toSeq.flatMap { line =>
      val f = line.split(",\\s*", -1)
      if (f.length < GpuFields.length) None
      else {
        val uuid = f(1).trim
        Some(
          GpuMetrics(
            server = server,
            collectedAt = at,
            gpuIndex = int(f(0)),
            uuid = uuid,
            name = f(2).trim,
            driverVersion = f(3).trim,
            utilizationGpuPercent = int(f(4)),
            utilizationMemPercent = int(f(5)),
            memUsedBytes = int(f(6)).toLong * MiB,
            memTotalBytes = int(f(7)).toLong * MiB,
            temperatureC = int(f(8)),
            powerDrawW = double(f(9)),
            powerLimitW = double(f(10)),
            fanPercent = value(f(11)).flatMap(v => Try(v.toDouble.toInt).toOption),
            smClockMhz = int(f(12)),
            memClockMhz = int(f(13)),
            pstate = value(f(14)).getOrElse(""),
            throttleReasons = throttleReasons(f(15)),
            processes = apps.collect { case (u, p) if u == uuid => p }
          )
        )
      }
    }

  /** Rows of the compute-apps query: (gpu uuid, process). */
  def parseApps(csv: String): Seq[(String, GpuProcess)] =
    csv.linesIterator.map(_.trim).filter(_.nonEmpty).toSeq.flatMap { line =>
      val f = line.split(",\\s*", -1)
      if (f.length < AppFields.length) None
      else Some(f(0).trim -> GpuProcess(int(f(1)).toLong, f(2).trim, int(f(3)).toLong * MiB))
    }
}

/** Runs nvidia-smi. `available` is false when the binary or the driver is missing. */
class NvidiaSmiReader(server: String, binary: String = "nvidia-smi") {
  import NvidiaSmi._

  private val logging: Logger = Logger(this.getClass)

  private def run(args: String*): Option[String] =
    Try {
      val process = new ProcessBuilder((binary +: args).asJava).redirectErrorStream(true).start()
      val reader = new BufferedReader(new InputStreamReader(process.getInputStream))
      val output = try reader.lines().iterator().asScala.mkString("\n") finally reader.close()
      if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new Exception("nvidia-smi timed out") }
      if (process.exitValue() != 0) throw new Exception(s"nvidia-smi exited ${process.exitValue()}: ${output.take(200)}")
      output
    }.fold(e => { logging.warn(s"Could not run $binary: ${e.getMessage}"); None }, Some(_))

  /** True when nvidia-smi runs and reports at least one GPU. */
  lazy val available: Boolean = run("--query-gpu=index", "--format=csv,noheader").exists(_.trim.nonEmpty)

  def sample(): Seq[GpuMetrics] = {
    val at = System.currentTimeMillis()
    val apps = run(s"--query-compute-apps=${AppFields.mkString(",")}", "--format=csv,noheader,nounits").map(parseApps).getOrElse(Seq())
    run(s"--query-gpu=${GpuFields.mkString(",")}", "--format=csv,noheader,nounits").map(parseGpus(_, server, at, apps)).getOrElse(Seq())
  }
}
