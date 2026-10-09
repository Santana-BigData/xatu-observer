package com.danielsanrocha.xatu.commons

import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.models.internals.{SensorKind, SensorReading}

import java.nio.file.{Files, Path}
import scala.concurrent.Future

class HardwareSensorsSpec extends UnitSpec {

  /** /sys/class/hwmon of a server with two sockets (like the R610) and a nic sensor. */
  private def fakeHwmon(): Path = {
    val root = Files.createTempDirectory("hwmon")
    val devices = Files.createTempDirectory("devices")
    def write(p: Path, s: String): Unit = { Files.createDirectories(p.getParent); Files.write(p, s.getBytes) }
    def chip(n: Int, name: String, device: String, temps: Seq[(Int, String, Int)]): Unit = {
      val dir = root.resolve(s"hwmon$n")
      write(dir.resolve("name"), name + "\n")
      val dev = Files.createDirectories(devices.resolve(device))
      Files.createSymbolicLink(dir.resolve("device"), dev)
      temps.foreach { case (i, label, milli) =>
        write(dir.resolve(s"temp${i}_label"), label + "\n")
        write(dir.resolve(s"temp${i}_input"), s"$milli\n")
        write(dir.resolve(s"temp${i}_max"), "87000\n")
        write(dir.resolve(s"temp${i}_crit"), "103000\n")
      }
    }
    // hwmon0 is the second socket, the order of hwmon is not the order of the sockets
    chip(0, "coretemp", "coretemp.1", Seq((2, "Core 0", 60000), (3, "Core 1", 61000)))
    chip(1, "coretemp", "coretemp.0", Seq((1, "Package id 0", 64000), (2, "Core 0", 63000), (11, "Core 10", 59000), (3, "Core 1", 62500)))
    chip(2, "i350bb", "0000:01:00.0", Seq((1, "loc1", 50000)))
    root
  }

  describe("coretemp") {
    it("should read every core of every socket, named by the socket, with the limits") {
      Future {
        val readings = HardwareSensors.coretemp(fakeHwmon(), "s1", 7)
        readings.map(r => (r.sensor, r.value)) should equal(
          Seq(("cpu0 package", 64.0), ("cpu0 core 0", 63.0), ("cpu0 core 1", 62.5), ("cpu0 core 10", 59.0), ("cpu1 core 0", 60.0), ("cpu1 core 1", 61.0))
        )
        readings.head should equal(SensorReading("s1", 7, "cpu0 package", SensorKind.CpuTemp, 64.0, Some(87.0), Some(103.0)))
      }
    }

    it("should return nothing without coretemp") {
      Future {
        HardwareSensors.coretemp(Files.createTempDirectory("empty"), "s1", 7) shouldBe empty
        HardwareSensors.coretemp(Path.of("/does/not/exist"), "s1", 7) shouldBe empty
      }
    }
  }

  describe("ipmiSdr") {
    // ipmitool sdr elist full of a PowerEdge R610 (trimmed)
    val r610 =
      """Temp             | 01h | ns  |  3.1 | Disabled
        |Ambient Temp     | 07h | ns  | 10.1 | Disabled
        |Ambient Temp     | 0Eh | ok  |  7.1 | 32 degrees C
        |Planar Temp      | 0Fh | ns  |  7.1 | Disabled
        |FAN MOD 1A RPM   | 30h | ok  |  7.1 | 5760 RPM
        |FAN MOD 1B RPM   | 36h | ok  |  7.1 | 4080 RPM
        |FAN MOD 2B RPM   | 37h | cr  |  7.1 | 0 RPM
        |Current 1        | 94h | ok  | 10.1 | 0.40 Amps
        |Voltage 1        | 96h | ok  | 10.1 | 228 Volts
        |System Level     | 98h | ok  |  7.1 | 210 Watts""".stripMargin

    it("should read fans and temperatures, ignoring disabled sensors and other units") {
      Future {
        HardwareSensors.ipmiSdr(r610, "s1", 7).map(r => (r.sensor, r.kind, r.value)) should equal(
          Seq(
            ("Ambient Temp", SensorKind.AmbientTemp, 32.0),
            ("FAN MOD 1A", SensorKind.Fan, 5760.0),
            ("FAN MOD 1B", SensorKind.Fan, 4080.0),
            // a dead fan is the most important reading
            ("FAN MOD 2B", SensorKind.Fan, 0.0)
          )
        )
      }
    }

    it("should tell apart sensors with the same name by the entity") {
      Future {
        val out = "Temp | 01h | ok | 3.1 | 40 degrees C\nTemp | 02h | ok | 3.2 | 42 degrees C\n"
        HardwareSensors.ipmiSdr(out, "s1", 7).map(r => (r.sensor, r.kind)) should equal(Seq(("Temp (3.1)", SensorKind.BoardTemp), ("Temp (3.2)", SensorKind.BoardTemp)))
      }
    }
  }

  describe("SensorsAggregator") {
    it("should keep min, average, max and last of each sensor per step") {
      Future {
        val r = (at: Long, v: Double) => SensorReading("s1", at * 1000, "cpu0 core 0", SensorKind.CpuTemp, v, Some(87), None)
        val points = SensorsAggregator.aggregate(Seq(r(0, 60), r(60, 70), r(90, 65), r(120, 50)), 120)
        points.map(p => (p.collectedAt, p.min, p.avg, p.max, p.last)) should equal(Seq((0L, 60.0, 65.0, 70.0, 65.0), (120000L, 50.0, 50.0, 50.0, 50.0)))
      }
    }
  }
}
