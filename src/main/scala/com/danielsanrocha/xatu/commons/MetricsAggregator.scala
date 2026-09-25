package com.danielsanrocha.xatu.commons

import com.danielsanrocha.xatu.models.internals.ServerMetrics

/**
 * Averages samples in buckets of `stepSeconds`, so a 7 days chart does not need
 * 10080 points per line. Each bucket is stamped with its start.
 */
object MetricsAggregator {

  def aggregate(samples: Seq[ServerMetrics], stepSeconds: Long): Seq[ServerMetrics] = {
    val stepMillis = stepSeconds * 1000
    samples
      .groupBy(s => s.collectedAt - Math.floorMod(s.collectedAt, stepMillis))
      .toSeq
      .sortBy(_._1)
      .map { case (bucket, group) => average(bucket, group) }
  }

  private def average(bucket: Long, group: Seq[ServerMetrics]): ServerMetrics = {
    val n = group.size.toDouble
    def avg(f: ServerMetrics => Double): Double = group.map(f).sum / n
    def avgLong(f: ServerMetrics => Long): Long = math.round(group.map(s => f(s).toDouble).sum / n)

    ServerMetrics(
      server = group.head.server,
      collectedAt = bucket,
      cpuPercent = avg(_.cpuPercent),
      cpuCores = group.map(_.cpuCores).max,
      load1m = avg(_.load1m),
      load5m = avg(_.load5m),
      load15m = avg(_.load15m),
      memTotalBytes = avgLong(_.memTotalBytes),
      memUsedBytes = avgLong(_.memUsedBytes),
      memAvailableBytes = avgLong(_.memAvailableBytes),
      swapTotalBytes = avgLong(_.swapTotalBytes),
      swapUsedBytes = avgLong(_.swapUsedBytes),
      diskTotalBytes = avgLong(_.diskTotalBytes),
      diskUsedBytes = avgLong(_.diskUsedBytes),
      netRxBytesPerSec = avg(_.netRxBytesPerSec),
      netTxBytesPerSec = avg(_.netTxBytesPerSec)
    )
  }

  /** Step used when the request does not give one: at most ~720 points per line. */
  def defaultStep(from: Long, to: Long, intervalSeconds: Long): Long = {
    val rangeSeconds = math.max(0L, (to - from) / 1000)
    val candidates = Seq(60L, 120L, 300L, 600L, 900L, 1800L, 3600L).filter(_ >= intervalSeconds)
    candidates.find(rangeSeconds / _ <= 720).getOrElse(candidates.lastOption.getOrElse(intervalSeconds))
  }
}
