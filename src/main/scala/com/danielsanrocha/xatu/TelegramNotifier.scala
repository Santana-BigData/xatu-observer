package com.danielsanrocha.xatu

import com.danielsanrocha.xatu.commons.StatusRules
import com.danielsanrocha.xatu.commons.StatusRules.Target
import com.danielsanrocha.xatu.models.internals.{CheckKind, SentAlert}
import com.danielsanrocha.xatu.repositories.{APIRepository, ContainerRepository, MetricsRepository, ServiceRepository, StatusRepository}
import com.typesafe.scalalogging.Logger
import scalaj.http.{Http, HttpOptions}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

/**
 * Sends the problems of every server to telegram. Every Xatu runs it, but only the
 * leader sends, so each alert goes once. Alerts are repeated every `repeatMinutes`
 * while the problem lasts, and a "resolved" message is sent when it is gone.
 */
class TelegramNotifier(
    token: String,
    chatId: String,
    leader: LeaderElection,
    statusRepository: StatusRepository,
    serviceRepository: ServiceRepository,
    apiRepository: APIRepository,
    containerRepository: ContainerRepository,
    metricsRepository: MetricsRepository,
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
    } yield services.map(s => Target(CheckKind.Service, s.id, s.name)) ++
      apis.map(a => Target(CheckKind.API, a.id, a.name)) ++
      containers.map(c => Target(CheckKind.Container, c.id, c.name))
    Await.result(all, 30.seconds)
  }

  override protected def run(): Unit =
    if (!leader.isLeader) logging.debug("Not the leader, not sending notifications.")
    else {
      val checks = Seq(CheckKind.Service, CheckKind.API, CheckKind.Container).map(k => k -> statusRepository.checks(k)).toMap
      val now = System.currentTimeMillis()
      val xatuServers = statusRepository.servers()
      val agents = Await.result(metricsRepository.servers(), 30.seconds)
      val problems = StatusRules.problems(targets(), checks, xatuServers) ++
        StatusRules.staleAgents(agents, xatuServers, now, agentStaleMinutes * 60 * 1000)
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

  private def notify(text: String): Unit = {
    logging.debug(s"TelegramNotifier message: $text")
    val result = Http(s"https://api.telegram.org/bot$token/sendMessage")
      .param("chat_id", chatId)
      .param("text", text)
      .option(HttpOptions.connTimeout(10000))
      .option(HttpOptions.readTimeout(10000))
      .execute()

    if (result.code != 200) throw new Exception(s"Error sending message to telegram! Status ${result.code}")
  }
}
