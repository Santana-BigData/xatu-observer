package com.danielsanrocha.xatu.commons

import com.danielsanrocha.xatu.models.internals.{SensorPoint, SensorReading}

/** Groups sensor readings in buckets of `stepSeconds`, per sensor, keeping min, average, max and last. */
object SensorsAggregator {

  def aggregate(readings: Seq[SensorReading], stepSeconds: Long): Seq[SensorPoint] = {
    val stepMillis = stepSeconds * 1000
    readings
      .groupBy(r => (r.sensor, r.collectedAt - Math.floorMod(r.collectedAt, stepMillis)))
      .toSeq
      .map { case ((sensor, bucket), group) =>
        val sorted = group.sortBy(_.collectedAt)
        val values = sorted.map(_.value)
        SensorPoint(sensor, sorted.last.kind, bucket, values.min, values.sum / values.size, values.max, values.last, sorted.last.high, sorted.last.crit)
      }
      .sortBy(p => (p.collectedAt, p.sensor))
  }
}
