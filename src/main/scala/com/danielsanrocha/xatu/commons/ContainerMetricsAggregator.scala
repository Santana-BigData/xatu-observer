package com.danielsanrocha.xatu.commons

import com.danielsanrocha.xatu.models.internals.ContainerMetrics

/**
 * Averages container samples in buckets of `stepSeconds`, per container and server (a
 * container that moved keeps one line per server). Each bucket is stamped with its start;
 * id, image and health are the last ones of the bucket.
 */
object ContainerMetricsAggregator {

  def aggregate(samples: Seq[ContainerMetrics], stepSeconds: Long): Seq[ContainerMetrics] = {
    val stepMillis = stepSeconds * 1000
    samples
      .groupBy(s => (s.containerName, s.server, s.collectedAt - Math.floorMod(s.collectedAt, stepMillis)))
      .toSeq
      .map { case ((_, _, bucket), group) => average(bucket, group.sortBy(_.collectedAt)) }
      .sortBy(m => (m.collectedAt, m.containerName, m.server))
  }

  private def average(bucket: Long, group: Seq[ContainerMetrics]): ContainerMetrics = {
    val n = group.size.toDouble
    def avg(f: ContainerMetrics => Double): Double = group.map(f).sum / n
    def avgLong(f: ContainerMetrics => Long): Long = math.round(group.map(s => f(s).toDouble).sum / n)
    val last = group.last
    last.copy(
      collectedAt = bucket,
      restartCount = group.map(_.restartCount).max,
      oomKilled = group.exists(_.oomKilled),
      cpuPercent = avg(_.cpuPercent),
      memUsageBytes = avgLong(_.memUsageBytes),
      memLimitBytes = avgLong(_.memLimitBytes),
      netRxBytesPerSec = avg(_.netRxBytesPerSec),
      netTxBytesPerSec = avg(_.netTxBytesPerSec),
      blockReadBytesPerSec = avg(_.blockReadBytesPerSec),
      blockWriteBytesPerSec = avg(_.blockWriteBytesPerSec),
      pids = math.round(avg(_.pids.toDouble)).toInt
    )
  }
}
