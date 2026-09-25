package com.danielsanrocha.xatu.observers

import com.danielsanrocha.xatu.models.internals.{CheckKind, ServerCheck, Service}
import com.danielsanrocha.xatu.repositories.StatusRepository
import com.typesafe.scalalogging.Logger

import java.io.{BufferedReader, InputStreamReader}

class ServiceObserver(s: Service, server: String, statusRepository: StatusRepository) extends Observer[Service](s) {
  private val logging: Logger = Logger(this.getClass)

  // avoids logging "not reporting" every 10 seconds
  private var reporting = true

  /** First line printed by `systemctl <args>` (empty when it prints nothing). */
  protected def systemctl(args: String*): String = {
    val command = scala.collection.JavaConverters.seqAsJavaList("systemctl" +: args)
    val process = new ProcessBuilder(command).redirectErrorStream(true).start()
    process.waitFor()
    val br = new BufferedReader(new InputStreamReader(process.getInputStream))
    try Option(br.readLine()).getOrElse("").trim
    finally br.close()
  }

  override lazy val task: Runnable = () => {
    try {
      logging.debug(s"Checking service ${_data.name}...")
      val active = systemctl("is-active", _data.name)
      logging.debug(s"Status for service ${_data.name}: $active")

      ServiceObserver.statusFor(active, systemctl("is-enabled", _data.name)) match {
        case Some(status) =>
          if (!reporting) logging.info(s"Service ${_data.name} is enabled on this server again, reporting its status.")
          reporting = true
          logging.debug(s"Service ${_data.name} status $status")
          save(status, if (status == 'F') Some(active) else None)
        case None =>
          if (reporting) logging.info(s"Service ${_data.name} is not enabled on this server, not reporting its status.")
          reporting = false
      }
    } catch {
      case e: Exception =>
        logging.error(s"Error retrieving service ${_data.name} status. Message:${e.getMessage}. Setting status to F...")
        save('F', Option(e.getMessage))
    }
  }

  private def save(status: Char, message: Option[String]): Unit =
    statusRepository.save(ServerCheck(CheckKind.Service, _data.id, server, status, message, System.currentTimeMillis()))
}

object ServiceObserver {

  // `systemctl is-enabled` states of units that are expected to run on this server
  private val expectedToRun = Set("enabled", "enabled-runtime", "static", "indirect", "generated", "transient", "alias", "linked", "linked-runtime")

  /**
   * Status to save for the service, or None when this server must not report it.
   *
   * tb_services is shared by every server (galera), so a server where the unit is
   * disabled, masked or missing would keep writing 'F' over the 'W' of the servers
   * that really run it. A running service is always 'W', even if disabled.
   */
  def statusFor(isActive: String, isEnabled: => String): Option[Char] =
    if (isActive == "active") Some('W')
    else if (expectedToRun.contains(isEnabled)) Some('F')
    else None
}
