package com.danielsanrocha.xatu.repositories

import com.danielsanrocha.xatu.models.internals.{SentAlert, ServerCheck, XatuServerInfo}

/**
 * Status of the checks, per server, in redis. Keys (always "::" separated):
 *
 *   xatu::status::<kind>::<id>::<server>  last check of <server>, expires if the server stops checking
 *   xatu::server::<server>                heartbeat of a Xatu instance, expires if it stops
 *   xatu::servers::known                  set with every server that ever sent a heartbeat
 *   xatu::leader                          server that runs the notifications (lock with ttl)
 *   xatu::alert::<key>                    alert already sent to telegram (to not repeat it)
 */
trait StatusRepository {
  def save(check: ServerCheck): Unit

  /** Current checks of a kind, grouped by target id. */
  def checks(kind: String): Map[Long, Seq[ServerCheck]]

  def heartbeat(server: String, at: Long): Unit

  /** Known servers, alive or not (their heartbeat expired). */
  def servers(): Seq[XatuServerInfo]

  /** Removes a server that was decommissioned from the known servers. */
  def forgetServer(server: String): Unit

  /** Takes or renews the leadership. True when `server` is the leader. */
  def acquireLeadership(server: String, ttlMillis: Long): Boolean

  def leader(): Option[String]

  /** Alerts already sent, by key. */
  def sentAlerts(): Map[String, SentAlert]

  def markAlertSent(key: String, alert: SentAlert): Unit

  def clearAlert(key: String): Unit
}
