package com.danielsanrocha.xatu.observers

import com.danielsanrocha.xatu.commons.ScriptRunner
import com.danielsanrocha.xatu.models.internals.{CheckKind, Script, ServerCheck}
import com.danielsanrocha.xatu.repositories.StatusRepository
import com.typesafe.scalalogging.Logger

/**
 * Runs the script once every `intervalSeconds` on this server. Runs never overlap (one
 * thread per observer). Fails on a non-zero exit or timeout.
 */
class ScriptObserver(
    s: Script,
    server: String,
    statusRepository: StatusRepository,
    run: (Script, Map[String, String]) => ScriptRunner.Result = (s, env) => ScriptRunner.run(s.script, s.timeoutSeconds, env)
) extends Observer[Script](s) {
  private val logging: Logger = Logger(this.getClass)

  override def periodSeconds: Long = _data.intervalSeconds.toLong

  /** The interval may have changed: schedule again. */
  override def reload(data: Script): Unit = {
    stop()
    _data = data
    start()
  }

  override lazy val task: Runnable = () => {
    val script = _data
    try {
      logging.debug(s"Running script ${script.name}...")
      val result = run(script, Map("XATU_SERVER" -> server, "XATU_SCRIPT_NAME" -> script.name))
      if (result.ok) {
        logging.debug(s"Script ${script.name} succeeded in ${result.durationMillis}ms")
        save(script, 'W', None)
      } else {
        val message = ScriptRunner.failureMessage(result, script.timeoutSeconds)
        logging.warn(s"Script ${script.name} failed in ${result.durationMillis}ms: $message")
        save(script, 'F', Some(message))
      }
    } catch {
      case _: InterruptedException =>
        logging.info(s"Script ${script.name} interrupted (observer stopped or reloaded)")
      case e: Exception =>
        logging.error(s"Error running script ${script.name}. Message: ${e.getMessage}. Setting status to F...")
        save(script, 'F', Option(e.getMessage))
    }
  }

  // the check must last until the next run ends, or the script would show as unknown between runs
  private def save(script: Script, status: Char, message: Option[String]): Unit =
    statusRepository.save(
      ServerCheck(CheckKind.Script, script.id, server, status, message, System.currentTimeMillis()),
      script.intervalSeconds.toLong + script.timeoutSeconds + 60
    )
}
