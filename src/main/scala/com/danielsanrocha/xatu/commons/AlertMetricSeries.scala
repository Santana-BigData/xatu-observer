package com.danielsanrocha.xatu.commons

import com.danielsanrocha.xatu.commons.AlertRules.Sample
import com.danielsanrocha.xatu.models.internals.{AlertMetric, SensorKind}
import com.danielsanrocha.xatu.repositories.{GpuMetricsRepository, MetricsRepository, SensorsRepository}

import scala.concurrent.{ExecutionContext, Future}

/** One value per sample for each metric of the alert rules, from the cassandra tables. */
class AlertMetricSeries(metrics: MetricsRepository, gpus: GpuMetricsRepository, sensors: SensorsRepository)(implicit ec: ExecutionContext) {

  private def percent(used: Long, total: Long): Option[Double] = if (total > 0) Some(used * 100.0 / total) else None

  private def sensorSeries(server: String, from: Long, to: Long, kind: String, pick: Seq[Double] => Double): Future[Seq[Sample]] =
    sensors.byServer(server, from, to).map { readings =>
      readings.filter(_.kind == kind).groupBy(_.collectedAt).toSeq.map { case (at, rs) => Sample(at, pick(rs.map(_.value))) }.sortBy(_.at)
    }

  def series(metric: String, server: String, from: Long, to: Long): Future[Seq[Sample]] =
    metric match {
      case AlertMetric.CpuTemp.key     => sensorSeries(server, from, to, SensorKind.CpuTemp, _.max)
      case AlertMetric.AmbientTemp.key => sensorSeries(server, from, to, SensorKind.AmbientTemp, _.max)
      case AlertMetric.FanRpm.key      => sensorSeries(server, from, to, SensorKind.Fan, _.min)
      case AlertMetric.GpuTemp.key =>
        gpus.byServer(server, from, to).map(_.groupBy(_.collectedAt).toSeq.map { case (at, gs) => Sample(at, gs.map(_.temperatureC.toDouble).max) }.sortBy(_.at))
      case AlertMetric.CpuPercent.key  => metrics.query(server, from, to).map(_.map(m => Sample(m.collectedAt, m.cpuPercent)))
      case AlertMetric.MemPercent.key  => metrics.query(server, from, to).map(_.flatMap(m => percent(m.memUsedBytes, m.memTotalBytes).map(Sample(m.collectedAt, _))))
      case AlertMetric.DiskPercent.key => metrics.query(server, from, to).map(_.flatMap(m => percent(m.diskUsedBytes, m.diskTotalBytes).map(Sample(m.collectedAt, _))))
      case other                       => Future.failed(new IllegalArgumentException(s"Unknown metric $other"))
    }

  /** Servers of a rule: the one it names, or every server that sent metrics in the last day. */
  def servers(ruleServer: String, now: Long): Future[Seq[String]] =
    if (ruleServer.trim.nonEmpty) Future.successful(Seq(ruleServer.trim))
    else metrics.servers().map(_.filter(s => now - s.lastSeen < 24L * 3600 * 1000).map(_.server).sorted)
}
