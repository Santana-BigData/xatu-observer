package com.danielsanrocha.xatu.controllers

import com.danielsanrocha.xatu.models.internals.{AlertMetric, NewAlertRule, RequestId}
import com.danielsanrocha.xatu.models.requests.{AlertRuleRequest, Id}
import com.danielsanrocha.xatu.models.responses.{AlertRulePreview, AlertRuleResponse, BacktestResponse, Created, Deleted, EpisodeResponse, HitsResult, ServerMessage}
import com.danielsanrocha.xatu.services.AlertRuleService
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.http.Request
import com.twitter.finatra.http.Controller
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

class AlertRuleController(service: AlertRuleService, intervalSeconds: Long)(implicit val ec: scala.concurrent.ExecutionContext) extends Controller {
  private val logging: Logger = Logger(this.getClass)

  private val PreviewHours = 24

  private def requestId: String = Contexts.local.get(RequestId).head.requestId

  /** Metrics a rule can watch, with unit and suggested operator/threshold. */
  get("/api/alert-rules/metrics") { _: Request =>
    HitsResult(AlertMetric.All.length, AlertMetric.All)
  }

  get("/api/alert-rules") { _: Request =>
    logging.info(s"(x-request-id - $requestId) List alert rules route called...")
    val now = System.currentTimeMillis()
    service.getAll().flatMap { rules =>
      // a rule whose metrics can not be read (cassandra down) is still listed, without state
      Future.sequence(rules.map(r => service.states(r, now).recover { case _ => Seq() }.map(AlertRuleResponse(r, _))))
    } map { rules => HitsResult(rules.length, rules) }
  }

  get("/api/alert-rule/:id") { id: Id =>
    service.getById(id.id) flatMap {
      case Some(rule) => service.states(rule, System.currentTimeMillis()).map(s => response.ok(AlertRuleResponse(rule, s)))
      case None       => Future.successful(response.notFound(ServerMessage(s"Alert rule ${id.id} not found", requestId)))
    }
  }

  /** State now and what the rule would have alerted in the last 24 hours, for a rule being edited. */
  post("/api/alert-rules/preview") { r: NewAlertRule =>
    logging.info(s"(x-request-id - $requestId) Preview alert rule route called...")
    val rule = r.copy(name = if (r.name.trim.isEmpty) "preview" else r.name).toRule(0)
    NewAlertRule.validate(r.copy(name = rule.name)) match {
      case Some(error) => Future.successful(response.badRequest(ServerMessage(error, requestId)))
      case None =>
        val now = System.currentTimeMillis()
        for {
          states <- service.states(rule, now)
          backtest <- service.backtest(rule, now, PreviewHours)
        } yield response.ok(
          AlertRulePreview(
            PreviewHours,
            states,
            backtest.map { b =>
              val episodes = b.episodes.map(e => EpisodeResponse(e.start, e.end, e.millis(intervalSeconds * 1000) / 60000, e.worst))
              BacktestResponse(b.server, episodes.size, episodes.map(_.minutes).sum, if (episodes.isEmpty) 0 else episodes.map(_.minutes).max, episodes)
            }
          )
        )
    }
  }

  post("/api/alert-rule") { r: NewAlertRule =>
    logging.info(s"(x-request-id - $requestId) Create alert rule route called...")
    service.create(r) map { id => response.ok(Created(id, requestId)) }
  }

  put("/api/alert-rule/:id") { r: AlertRuleRequest =>
    logging.info(s"(x-request-id - $requestId) Update alert rule ${r.id} route called...")
    service.update(r.id, NewAlertRule(r.name, r.metric, r.server, r.operator, r.threshold, r.durationMinutes, r.enabled)) map { _ =>
      response.ok(ServerMessage(s"Updated alert rule ${r.id}", requestId))
    }
  }

  delete("/api/alert-rule/:id") { id: Id =>
    logging.info(s"(x-request-id - $requestId) Delete alert rule ${id.id} route called...")
    service.delete(id.id) map {
      case true  => response.ok(Deleted(id.id, requestId))
      case false => response.notFound(ServerMessage(s"Alert rule ${id.id} not found", requestId))
    }
  }
}
