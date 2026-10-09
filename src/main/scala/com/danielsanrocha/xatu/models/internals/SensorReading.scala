package com.danielsanrocha.xatu.models.internals

/**
 * One hardware sensor of a server at a moment.
 *
 * kind: "cpu_temp" (coretemp, one per core and package, °C), "fan" (ipmi, RPM),
 * "ambient_temp" (ipmi, air inlet: "Ambient Temp" on the R610, "Inlet Temp" on the T620) and "board_temp" (other ipmi temperatures, °C). `high` and `crit` are the limits the
 * hardware reports (coretemp max/crit), when it does.
 */
case class SensorReading(
    server: String,
    collectedAt: Long,
    sensor: String,
    kind: String,
    value: Double,
    high: Option[Double],
    crit: Option[Double]
)

object SensorKind {
  val CpuTemp = "cpu_temp"
  val Fan = "fan"
  val AmbientTemp = "ambient_temp"
  val BoardTemp = "board_temp"
}

/** Sensor samples of a bucket: lowest, average and highest value, and the last one. */
case class SensorPoint(
    sensor: String,
    kind: String,
    collectedAt: Long,
    min: Double,
    avg: Double,
    max: Double,
    last: Double,
    high: Option[Double],
    crit: Option[Double]
)
