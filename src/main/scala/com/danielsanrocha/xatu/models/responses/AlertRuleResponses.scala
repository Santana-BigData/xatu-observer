package com.danielsanrocha.xatu.models.responses

import com.danielsanrocha.xatu.commons.AlertRules.ServerState
import com.danielsanrocha.xatu.models.internals.AlertRule

import java.sql.Timestamp

/** Rule with its state now on each server. */
case class AlertRuleResponse(
    id: Long,
    name: String,
    metric: String,
    server: String,
    operator: String,
    threshold: Double,
    durationMinutes: Int,
    enabled: Boolean,
    firing: Boolean,
    createDate: Timestamp,
    updateDate: Timestamp,
    servers: Seq[ServerState]
)

object AlertRuleResponse {
  def apply(r: AlertRule, servers: Seq[ServerState]): AlertRuleResponse =
    AlertRuleResponse(r.id, r.name, r.metric, r.server, r.operator, r.threshold, r.durationMinutes, r.enabled, r.enabled && servers.exists(_.firing), r.createDate, r.updateDate, servers)
}

case class EpisodeResponse(start: Long, end: Long, minutes: Long, worst: Double)

/** What the rule would have alerted on a server in the last hours. */
case class BacktestResponse(server: String, count: Int, totalMinutes: Long, longestMinutes: Long, episodes: Seq[EpisodeResponse])

case class AlertRulePreview(hours: Int, servers: Seq[ServerState], backtest: Seq[BacktestResponse])
