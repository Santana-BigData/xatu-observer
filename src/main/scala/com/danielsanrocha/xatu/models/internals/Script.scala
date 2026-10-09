package com.danielsanrocha.xatu.models.internals

import java.sql.Timestamp

/** Bash script run once every `intervalSeconds` on every server; it fails on a non-zero exit or timeout. */
case class Script(
    override val id: Long,
    override val name: String,
    script: String,
    intervalSeconds: Int,
    timeoutSeconds: Int,
    createDate: Timestamp,
    updateDate: Timestamp
) extends Data(id, name) {
  override def configuration: Any = (id, name, script, intervalSeconds, timeoutSeconds)
}

case class NewScript(
    name: String,
    script: String,
    intervalSeconds: Int,
    timeoutSeconds: Int
)

object NewScript {
  val MinIntervalSeconds = 10
  val MaxIntervalSeconds: Int = 24 * 3600
  val MaxTimeoutSeconds = 3600
  val MaxScriptLength = 16000

  /** Why the script can not be saved, or None when it is valid. */
  def validate(s: NewScript): Option[String] =
    if (s.name.trim.isEmpty || s.name.length > 200) Some("name is required (up to 200 characters)")
    else if (s.script.trim.isEmpty) Some("script is required")
    else if (s.script.length > MaxScriptLength) Some(s"script is too long (up to $MaxScriptLength characters)")
    else if (s.intervalSeconds < MinIntervalSeconds || s.intervalSeconds > MaxIntervalSeconds)
      Some(s"interval_seconds must be between $MinIntervalSeconds and $MaxIntervalSeconds")
    else if (s.timeoutSeconds < 1 || s.timeoutSeconds > MaxTimeoutSeconds || s.timeoutSeconds > s.intervalSeconds)
      Some(s"timeout_seconds must be between 1 and $MaxTimeoutSeconds and not more than interval_seconds")
    else None
}
