package com.danielsanrocha.xatu.services

import com.danielsanrocha.xatu.commons.AlertRules.{Episode, ServerState}
import com.danielsanrocha.xatu.commons.StatusRules.Problem
import com.danielsanrocha.xatu.commons.{AlertMetricSeries, AlertRules}
import com.danielsanrocha.xatu.exceptions.{BadArgumentException, NotFoundException}
import com.danielsanrocha.xatu.models.internals.{AlertRule, NewAlertRule}
import com.danielsanrocha.xatu.repositories.AlertRuleRepository

import scala.concurrent.{ExecutionContext, Future}

/** Rules, their state on each server, the preview of a rule being edited and the problems sent to telegram. */
class AlertRuleService(repository: AlertRuleRepository, series: AlertMetricSeries, intervalSeconds: Long)(implicit ec: ExecutionContext) {
  private val intervalMillis = intervalSeconds * 1000

  case class Backtest(server: String, episodes: Seq[Episode])

  private def validated[T](r: NewAlertRule)(f: => Future[T]): Future[T] =
    NewAlertRule.validate(r) match {
      case Some(error) => Future.failed(new BadArgumentException(error))
      case None        => f
    }

  def getAll(): Future[Seq[AlertRule]] = repository.getAll()

  def getById(id: Long): Future[Option[AlertRule]] = repository.getById(id)

  def create(r: NewAlertRule): Future[Long] = validated(r)(repository.create(r))

  def update(id: Long, r: NewAlertRule): Future[Boolean] = validated(r) {
    repository.update(id, r).flatMap {
      case true  => Future.successful(true)
      case false => Future.failed(new NotFoundException(s"Alert rule with id $id not found"))
    }
  }

  def delete(id: Long): Future[Boolean] = repository.delete(id)

  /** State of the rule on each of its servers, looking back a bit more than its duration. */
  def states(rule: AlertRule, now: Long): Future[Seq[ServerState]] =
    series.servers(rule.server, now).flatMap { servers =>
      val from = now - rule.durationMinutes * 60000L - 5 * intervalMillis
      Future.sequence(servers.map { s =>
        series.series(rule.metric, s, from, now).map(AlertRules.state(s, _, rule, now, intervalMillis))
      })
    }

  /** What the rule would have alerted in the last `hours` on each server. */
  def backtest(rule: AlertRule, now: Long, hours: Int): Future[Seq[Backtest]] =
    series.servers(rule.server, now).flatMap { servers =>
      Future.sequence(servers.map { s =>
        series.series(rule.metric, s, now - hours * 3600000L, now).map(samples => Backtest(s, AlertRules.episodes(samples, rule, intervalMillis)))
      })
    }

  /** Firing rules, one problem per rule and server. */
  def problems(now: Long): Future[Seq[Problem]] =
    repository.getAll().flatMap { rules =>
      Future.sequence(rules.filter(_.enabled).map { rule =>
        states(rule, now).map(_.filter(_.firing).map(st => Problem(s"rule::${rule.id}::${st.server}", AlertRules.message(rule, st, now))))
      })
    }.map(_.flatten)
}
