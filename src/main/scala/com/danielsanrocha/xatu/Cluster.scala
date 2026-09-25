package com.danielsanrocha.xatu

import com.danielsanrocha.xatu.repositories.StatusRepository
import com.typesafe.scalalogging.Logger

import java.util.concurrent.{ScheduledThreadPoolExecutor, TimeUnit}

/** Runs `task` every `periodSeconds`, logging (never propagating) its errors. */
abstract class Periodic(name: String, periodSeconds: Long) {
  private val logging: Logger = Logger(this.getClass)
  private val ex = new ScheduledThreadPoolExecutor(1)

  protected def run(): Unit

  def start(): Unit = {
    logging.info(s"Starting $name, every $periodSeconds seconds...")
    ex.scheduleAtFixedRate(
      () =>
        try run()
        catch { case e: Throwable => logging.error(s"Error in $name. Message: ${e.getMessage}") },
      0,
      periodSeconds,
      TimeUnit.SECONDS
    )
  }
}

/** Tells the other Xatus this server is alive; without it, its checks are considered stale. */
class ServerHeartbeat(server: String, repository: StatusRepository, periodSeconds: Long) extends Periodic("ServerHeartbeat", periodSeconds) {
  override protected def run(): Unit = repository.heartbeat(server, System.currentTimeMillis())
}

/**
 * Only one Xatu (the leader) sends notifications. The leadership is a redis lock renewed
 * every period; if the leader stops, the lock expires and another Xatu takes it.
 */
class LeaderElection(server: String, repository: StatusRepository, periodSeconds: Long) extends Periodic("LeaderElection", periodSeconds) {
  private val logging: Logger = Logger(this.getClass)

  @volatile private var leader = false

  def isLeader: Boolean = leader

  override protected def run(): Unit = {
    val now = repository.acquireLeadership(server, periodSeconds * 3 * 1000)
    if (now != leader) logging.info(if (now) s"$server is now the leader (sends notifications)" else s"$server is not the leader anymore")
    leader = now
  }
}
