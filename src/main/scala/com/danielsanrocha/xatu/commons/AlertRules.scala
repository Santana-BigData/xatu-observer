package com.danielsanrocha.xatu.commons

import com.danielsanrocha.xatu.models.internals.{AlertMetric, AlertOperator, AlertRule}

/**
 * Evaluation of the alert rules over the samples of a server (one value per sample).
 * Pure functions: the notifier, the rules page and the preview use the same logic.
 *
 * A run is a sequence of samples in the condition with no gap longer than `maxGap`
 * (a server that stopped sending breaks it). It fires when it lasts `durationMinutes`.
 */
object AlertRules {

  case class Sample(at: Long, value: Double)

  /** A run of samples in the condition: from `start` to `end`, with the worst value. */
  case class Episode(start: Long, end: Long, worst: Double, last: Double) {
    def millis(intervalMillis: Long): Long = end - start + intervalMillis
  }

  /** How a server is now for a rule. `in_condition_since` is set while in the condition. */
  case class ServerState(
      server: String,
      current: Option[Double],
      lastAt: Option[Long],
      inConditionSince: Option[Long],
      worst: Option[Double],
      firing: Boolean
  )

  def matches(rule: AlertRule, value: Double): Boolean =
    if (rule.operator == AlertOperator.Below) value < rule.threshold else value > rule.threshold

  private def worse(rule: AlertRule, a: Double, b: Double): Double =
    if (rule.operator == AlertOperator.Below) math.min(a, b) else math.max(a, b)

  /** Every run of samples in the condition, oldest first. */
  def runs(samples: Seq[Sample], rule: AlertRule, intervalMillis: Long): Seq[Episode] = {
    val maxGap = 3 * intervalMillis
    val sorted = samples.sortBy(_.at)
    val result = Seq.newBuilder[Episode]
    var current: Option[Episode] = None
    var previousAt = Long.MinValue
    sorted.foreach { s =>
      val continues = s.at - previousAt <= maxGap
      if (matches(rule, s.value))
        current = current match {
          case Some(e) if continues => Some(e.copy(end = s.at, worst = worse(rule, e.worst, s.value), last = s.value))
          case other =>
            other.foreach(result += _)
            Some(Episode(s.at, s.at, s.value, s.value))
        }
      else {
        current.foreach(result += _)
        current = None
      }
      previousAt = s.at
    }
    current.foreach(result += _)
    result.result()
  }

  /** Runs long enough to fire: what the rule would have alerted. */
  def episodes(samples: Seq[Sample], rule: AlertRule, intervalMillis: Long): Seq[Episode] =
    runs(samples, rule, intervalMillis).filter(_.millis(intervalMillis) >= rule.durationMinutes * 60000L)

  /** State now: the last sample must be recent (3 intervals), or nothing is known. */
  def state(server: String, samples: Seq[Sample], rule: AlertRule, now: Long, intervalMillis: Long): ServerState = {
    val last = samples.sortBy(_.at).lastOption.filter(s => now - s.at <= 3 * intervalMillis)
    val run = last.flatMap(l => runs(samples, rule, intervalMillis).lastOption.filter(_.end == l.at))
    ServerState(
      server,
      current = last.map(_.value),
      lastAt = last.map(_.at),
      inConditionSince = run.map(_.start),
      worst = run.map(_.worst),
      firing = run.exists(_.millis(intervalMillis) >= rule.durationMinutes * 60000L)
    )
  }

  def format(value: Double): String =
    if (value == math.rint(value)) value.toLong.toString else f"$value%.1f"

  /** Telegram message of a firing rule. */
  def message(rule: AlertRule, state: ServerState, now: Long): String = {
    val metric = AlertMetric.get(rule.metric)
    val unit = metric.map(_.unit).getOrElse("")
    val label = metric.map(_.label).getOrElse(rule.metric)
    val minutes = state.inConditionSince.map(s => (now - s) / 60000).getOrElse(0L)
    s"$label on ${state.server} ${rule.operator} ${format(rule.threshold)}$unit for $minutes min " +
      s"(now ${state.current.map(format).getOrElse("?")}$unit, worst ${state.worst.map(format).getOrElse("?")}$unit) - rule '${rule.name}'"
  }
}
