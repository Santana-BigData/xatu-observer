package com.danielsanrocha.xatu.commons

import com.danielsanrocha.xatu.models.internals.GpuMetrics

/** Averages GPU samples in buckets of `stepSeconds`, per server and gpu. Text fields are the last of the bucket. */
object GpuMetricsAggregator {

  def aggregate(samples: Seq[GpuMetrics], stepSeconds: Long): Seq[GpuMetrics] = {
    val stepMillis = stepSeconds * 1000
    samples
      .groupBy(s => (s.server, s.gpuIndex, s.collectedAt - Math.floorMod(s.collectedAt, stepMillis)))
      .toSeq
      .map { case ((_, _, bucket), group) => average(bucket, group.sortBy(_.collectedAt)) }
      .sortBy(m => (m.collectedAt, m.server, m.gpuIndex))
  }

  private def average(bucket: Long, group: Seq[GpuMetrics]): GpuMetrics = {
    val n = group.size.toDouble
    def avg(f: GpuMetrics => Double): Double = group.map(f).sum / n
    def avgInt(f: GpuMetrics => Int): Int = math.round(avg(f(_).toDouble)).toInt
    val last = group.last
    val fans = group.flatMap(_.fanPercent)
    last.copy(
      collectedAt = bucket,
      utilizationGpuPercent = avgInt(_.utilizationGpuPercent),
      utilizationMemPercent = avgInt(_.utilizationMemPercent),
      memUsedBytes = math.round(avg(_.memUsedBytes.toDouble)),
      temperatureC = avgInt(_.temperatureC),
      powerDrawW = avg(_.powerDrawW),
      fanPercent = if (fans.isEmpty) None else Some(math.round(fans.sum.toDouble / fans.size).toInt),
      smClockMhz = avgInt(_.smClockMhz),
      memClockMhz = avgInt(_.memClockMhz),
      // any throttling in the bucket is worth showing
      throttleReasons = group.flatMap(_.throttleReasons).distinct
    )
  }
}
