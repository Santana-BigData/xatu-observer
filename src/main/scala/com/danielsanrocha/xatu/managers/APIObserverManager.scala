package com.danielsanrocha.xatu.managers

import com.danielsanrocha.xatu.models.internals.API
import com.typesafe.scalalogging.Logger
import com.danielsanrocha.xatu.observers.APIObserver
import com.danielsanrocha.xatu.services.APIService

class APIObserverManager(implicit val service: APIService, override implicit val ec: scala.concurrent.ExecutionContext) extends Manager[API, APIObserver](service, ec) {
  private val logging: Logger = Logger(this.getClass)
  logging.info("Starting APIObserverManager...")
  override def createObserver(data: API): APIObserver = new APIObserver(data, service, ec)
}
