package com.danielsanrocha.xatu

import com.danielsanrocha.xatu.commons.{StatusRules, TelegramClient}
import com.danielsanrocha.xatu.commons.StatusRules.Target
import com.danielsanrocha.xatu.models.internals.{CheckKind, SentAlert, TelegramChat}
import com.danielsanrocha.xatu.services.AlertRuleService
import com.danielsanrocha.xatu.repositories.{APIRepository, ContainerRepository, MetricsRepository, ScriptRepository, ServiceRepository, StatusRepository, TelegramChatRepository}
import com.typesafe.scalalogging.Logger

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

/**
 * Sends the problems of every server to telegram. Every Xatu runs it, but only the
 * leader sends, so each alert goes once. Alerts are repeated every `repeatMinutes`
 * while the problem lasts, and a "resolved" message is sent when it is gone.
 *
 * Every alert goes to TELEGRAM_CHAT_ID and to the enabled chats of tb_telegram_chats.
 */
class TelegramNotifier(
    telegram: TelegramClient,
    chatId: String,
    chatRepository: TelegramChatRepository,
    leader: LeaderElection,
    statusRepository: StatusRepository,
    serviceRepository: ServiceRepository,
    apiRepository: APIRepository,
    containerRepository: ContainerRepository,
    scriptRepository: ScriptRepository,
    metricsRepository: MetricsRepository,
    alertRules: AlertRuleService,
    repeatMinutes: Long,
    agentStaleMinutes: Long,
    confirmRounds: Int
)(implicit val ec: ExecutionContext)
    extends Periodic("TelegramNotifier", 60) {
  private val logging: Logger = Logger(this.getClass)

  // problem keys of the last rounds: a problem is alerted only after `confirmRounds` rounds in a row
  private var rounds: Seq[Set[String]] = Seq()

  private def targets(): Seq[Target] = {
    val all = for {
      services <- serviceRepository.getAll(1000, 0)
      apis <- apiRepository.getAll(1000, 0)
      containers <- containerRepository.getAll(1000, 0)
      scripts <- scriptRepository.getAll(1000, 0)
    } yield services.map(s => Target(CheckKind.Service, s.id, s.name)) ++
      apis.map(a => Target(CheckKind.API, a.id, a.name)) ++
      containers.map(c => Target(CheckKind.Container, c.id, c.name)) ++
      scripts.map(s => Target(CheckKind.Script, s.id, s.name))
    Await.result(all, 30.seconds)
  }

  override protected def run(): Unit =
    if (!leader.isLeader) logging.debug("Not the leader, not sending notifications.")
    else {
      val checks = Seq(CheckKind.Service, CheckKind.API, CheckKind.Container, CheckKind.Script).map(k => k -> statusRepository.checks(k)).toMap
      val now = System.currentTimeMillis()
      val xatuServers = statusRepository.servers()
      val agents = Await.result(metricsRepository.servers(), 30.seconds)
      // rules over the metrics (temperature, fans...): cassandra down must not stop the other alerts
      val ruleProblems =
        try Await.result(alertRules.problems(now), 30.seconds)
        catch { case e: Exception => logging.error(s"Could not evaluate the alert rules: ${e.getMessage}"); Seq() }
      val problems = StatusRules.problems(targets(), checks, xatuServers) ++
        StatusRules.staleAgents(agents, xatuServers, now, agentStaleMinutes * 60 * 1000) ++ ruleProblems
      rounds = (rounds :+ problems.map(_.key).toSet).takeRight(confirmRounds)
      val confirmed = StatusRules.confirmedKeys(rounds, confirmRounds)
      val plan = StatusRules.plan(problems, statusRepository.sentAlerts(), now, repeatMinutes * 60 * 1000, confirmed)

      logging.info(s"${problems.size} problems (${confirmed.size} confirmed), sending ${plan.send.size} alerts and ${plan.resolved.size} resolved")

      plan.send.foreach { p =>
        notify(s"🔴 ${p.message}")
        statusRepository.markAlertSent(p.key, SentAlert(now, p.message))
      }
      plan.resolved.foreach { case (key, alert) =>
        notify(s"🟢 Resolved: ${alert.message}")
        statusRepository.clearAlert(key)
      }
    }

  /**
   * Sends to every recipient. A chat that fails (blocked the bot, wrong id) does not stop
   * the others; only when all fail it throws, so the alert is tried again next round.
   */
  private def notify(text: String): Unit = {
    logging.debug(s"TelegramNotifier message: $text")
    val chats =
      try Await.result(chatRepository.getAll(), 30.seconds)
      catch { case e: Exception => logging.error(s"Could not read tb_telegram_chats, sending only to TELEGRAM_CHAT_ID: ${e.getMessage}"); Seq() }
    val recipients = TelegramNotifier.recipients(chatId, chats)
    val failures = recipients.flatMap(r => telegram.send(r, text).left.toOption.map(r -> _))
    failures.foreach { case (r, reason) => logging.error(s"Could not send the alert to telegram chat $r: $reason") }
    if (recipients.nonEmpty && failures.size == recipients.size) throw new Exception(s"Error sending message to telegram: ${failures.head._2}")
  }
}

object TelegramNotifier {

  /** TELEGRAM_CHAT_ID first, then the enabled chats, without repeating a chat. */
  def recipients(primary: String, chats: Seq[TelegramChat]): Seq[String] =
    (Option(primary).map(_.trim).filter(_.nonEmpty).toSeq ++ chats.filter(_.enabled).map(_.chatId.trim)).distinct
}
