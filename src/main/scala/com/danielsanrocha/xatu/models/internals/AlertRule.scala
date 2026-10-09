package com.danielsanrocha.xatu.models.internals

import java.sql.Timestamp

/**
 * Alert when `metric` of a server stays above (or below) `threshold` for more than
 * `durationMinutes`. `server` empty means every server that sends metrics.
 */
case class AlertRule(
    id: Long,
    name: String,
    metric: String,
    server: String,
    operator: String,
    threshold: Double,
    durationMinutes: Int,
    enabled: Boolean,
    createDate: Timestamp,
    updateDate: Timestamp
)

case class NewAlertRule(
    name: String,
    metric: String,
    server: String = "",
    operator: String,
    threshold: Double,
    durationMinutes: Int,
    enabled: Boolean = true
) {
  def toRule(id: Long): AlertRule = AlertRule(id, name, metric, server, operator, threshold, durationMinutes, enabled, new Timestamp(0), new Timestamp(0))
}

object NewAlertRule {
  val MaxDurationMinutes: Int = 24 * 60

  def validate(r: NewAlertRule): Option[String] =
    if (r.name.trim.isEmpty || r.name.length > 200) Some("name is required (up to 200 characters)")
    else if (AlertMetric.get(r.metric).isEmpty) Some(s"metric must be one of ${AlertMetric.All.map(_.key).mkString(", ")}")
    else if (r.operator != AlertOperator.Above && r.operator != AlertOperator.Below) Some("operator must be above or below")
    else if (r.threshold.isNaN || r.threshold.isInfinite) Some("threshold must be a number")
    else if (r.durationMinutes < 1 || r.durationMinutes > MaxDurationMinutes) Some(s"duration_minutes must be between 1 and $MaxDurationMinutes")
    else if (r.server.length > 200) Some("server is too long")
    else None
}

object AlertOperator {
  val Above = "above"
  val Below = "below"
}

/** A metric rules can watch. One value per server per sample, e.g. the hottest core. */
case class AlertMetric(key: String, label: String, unit: String, defaultOperator: String, defaultThreshold: Double)

object AlertMetric {
  val CpuTemp: AlertMetric = AlertMetric("cpu_temp", "CPU temperature (hottest core)", "°C", AlertOperator.Above, 80)
  val GpuTemp: AlertMetric = AlertMetric("gpu_temp", "GPU temperature (hottest GPU)", "°C", AlertOperator.Above, 80)
  val AmbientTemp: AlertMetric = AlertMetric("ambient_temp", "Inlet air temperature (ipmi)", "°C", AlertOperator.Above, 35)
  val FanRpm: AlertMetric = AlertMetric("fan_rpm", "Fan speed (slowest fan, ipmi)", "RPM", AlertOperator.Below, 1500)
  val CpuPercent: AlertMetric = AlertMetric("cpu_percent", "CPU usage", "%", AlertOperator.Above, 90)
  val MemPercent: AlertMetric = AlertMetric("mem_percent", "Memory usage", "%", AlertOperator.Above, 90)
  val DiskPercent: AlertMetric = AlertMetric("disk_percent", "Disk usage", "%", AlertOperator.Above, 90)

  val All: Seq[AlertMetric] = Seq(CpuTemp, GpuTemp, AmbientTemp, FanRpm, CpuPercent, MemPercent, DiskPercent)

  def get(key: String): Option[AlertMetric] = All.find(_.key == key)
}
