package com.danielsanrocha.xatu.models.internals

/**
 * Result of one check made by one server. Each Xatu writes only its own checks, so
 * servers never overwrite each other (they used to share the status column in mariadb).
 *
 * kind: "service", "api" or "container". status: 'W' working, 'F' failing.
 */
case class ServerCheck(
    kind: String,
    targetId: Long,
    server: String,
    status: Char,
    message: Option[String],
    checkedAt: Long
)

/** Alert already sent to telegram, kept to not repeat it and to tell when it is resolved. */
case class SentAlert(at: Long, message: String)

/** A Xatu instance that sends heartbeats. */
case class XatuServerInfo(
    server: String,
    alive: Boolean,
    lastSeen: Option[Long]
)

object CheckKind {
  val Service = "service"
  val API = "api"
  val Container = "container"
}
