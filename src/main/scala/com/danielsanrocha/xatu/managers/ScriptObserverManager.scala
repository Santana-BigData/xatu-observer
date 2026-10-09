package com.danielsanrocha.xatu.managers

import com.danielsanrocha.xatu.models.internals.Script
import com.danielsanrocha.xatu.observers.ScriptObserver
import com.danielsanrocha.xatu.repositories.StatusRepository
import com.danielsanrocha.xatu.services.ScriptService
import com.typesafe.scalalogging.Logger

class ScriptObserverManager(server: String, statusRepository: StatusRepository)(
    implicit val service: ScriptService,
    override implicit val ec: scala.concurrent.ExecutionContext
) extends Manager[Script, ScriptObserver](service, ec) {
  private val logging: Logger = Logger(this.getClass)
  logging.info("Starting ScriptObserverManager")

  override protected def createObserver(data: Script): ScriptObserver = {
    new ScriptObserver(data, server, statusRepository)
  }
}
