package com.danielsanrocha.xatu.commons

import com.danielsanrocha.xatu.models.internals.{SensorKind, SensorReading}
import com.typesafe.scalalogging.Logger

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._
import scala.util.Try

/** Parsers of the hardware sensors. Pure functions, tested with real outputs. */
object HardwareSensors {

  /**
   * Temperatures of every core and package from the coretemp hwmon devices under `root`
   * (/sys/class/hwmon). The socket comes from the device (coretemp.0, coretemp.1), so two
   * sockets with "Core 0" become "cpu0 core 0" and "cpu1 core 0".
   */
  def coretemp(root: Path, server: String, at: Long): Seq[SensorReading] = {
    val dirs = Try(Files.list(root).iterator().asScala.toSeq).getOrElse(Seq())
    dirs
      .filter(d => read(d.resolve("name")).contains("coretemp"))
      .flatMap { dir =>
        val socket = Try(dir.resolve("device").toRealPath().getFileName.toString).toOption
          .flatMap(n => "\\d+$".r.findFirstIn(n))
          .getOrElse("0")
        val labels = Try(Files.list(dir).iterator().asScala.toSeq).getOrElse(Seq()).filter(_.getFileName.toString.matches("temp\\d+_label"))
        labels.flatMap { labelFile =>
          val base = labelFile.getFileName.toString.stripSuffix("_label")
          for {
            label <- read(labelFile)
            value <- milli(dir.resolve(s"${base}_input"))
          } yield SensorReading(server, at, s"cpu$socket ${sensorName(label)}", SensorKind.CpuTemp, value, milli(dir.resolve(s"${base}_max")), milli(dir.resolve(s"${base}_crit")))
        }
      }
      .sortBy(r => sortKey(r.sensor))
  }

  /** "Core 3" -> "core 3", "Package id 1" -> "package". */
  private def sensorName(label: String): String =
    if (label.toLowerCase.startsWith("package")) "package" else label.toLowerCase

  // cpu0 package, cpu0 core 0, cpu0 core 1, ..., cpu0 core 10
  private def sortKey(sensor: String): (String, Int, Int) = {
    val parts = sensor.split(" ")
    val core = if (parts.length > 2) Try(parts(2).toInt).getOrElse(0) else -1
    (parts.head, if (parts(1) == "package") 0 else 1, core)
  }

  private def read(p: Path): Option[String] = Try(new String(Files.readAllBytes(p)).trim).toOption.filter(_.nonEmpty)

  private def milli(p: Path): Option[Double] = read(p).flatMap(v => Try(v.toDouble / 1000).toOption)

  private val Reading = """^([0-9]+(?:\.[0-9]+)?) (RPM|degrees C)$""".r

  /**
   * Fans and temperatures of `ipmitool sdr elist full`:
   *   FAN MOD 1A RPM   | 30h | ok  |  7.1 | 5880 RPM
   *   Ambient Temp     | 0Eh | ok  |  7.1 | 33 degrees C
   * Disabled sensors ("ns") and other units (volts, amps, watts) are ignored.
   */
  def ipmiSdr(output: String, server: String, at: Long): Seq[SensorReading] = {
    val rows = output.linesIterator.toSeq.flatMap { line =>
      val f = line.split("\\|").map(_.trim)
      if (f.length < 5 || f(2) == "ns") None
      else
        f(4) match {
          case Reading(value, "RPM") => Some((f(0).stripSuffix(" RPM").trim, f(3), SensorKind.Fan, value.toDouble))
          case Reading(value, _) =>
            Some((f(0), f(3), if (f(0).toLowerCase.contains("ambient") || f(0).toLowerCase.contains("inlet")) SensorKind.AmbientTemp else SensorKind.BoardTemp, value.toDouble))
          case _ => None
        }
    }
    // the same name on two entities (e.g. "Temp" of each cpu) gets the entity
    val counts = rows.groupBy(_._1).view.mapValues(_.size).toMap
    rows.map { case (name, entity, kind, value) =>
      SensorReading(server, at, if (counts(name) > 1) s"$name ($entity)" else name, kind, value, None, None)
    }
  }
}

/**
 * Reads the sensors of this server: coretemp always, ipmi when `ipmiCommand` works
 * (it needs root: e.g. "sudo -n /usr/bin/ipmitool sdr elist full" with a sudoers rule).
 */
class HardwareSensorsReader(server: String, ipmiCommand: String, hwmonRoot: Path = Paths.get("/sys/class/hwmon")) {
  private val logging: Logger = Logger(this.getClass)

  private def ipmi(at: Long): Option[Seq[SensorReading]] =
    if (ipmiCommand.trim.isEmpty) None
    else {
      val result = ScriptRunner.run(ipmiCommand, 30)
      if (result.ok) Some(HardwareSensors.ipmiSdr(result.output, server, at))
      else {
        logging.warn(s"Could not read ipmi sensors with '$ipmiCommand': ${ScriptRunner.failureMessage(result, 30)}")
        None
      }
    }

  /** True when ipmi works and has fans or temperatures; checked once, at start. */
  lazy val ipmiAvailable: Boolean = ipmi(System.currentTimeMillis()).exists(_.nonEmpty)

  def sample(): Seq[SensorReading] = {
    val at = System.currentTimeMillis()
    HardwareSensors.coretemp(hwmonRoot, server, at) ++ (if (ipmiAvailable) ipmi(at).getOrElse(Seq()) else Seq())
  }
}
