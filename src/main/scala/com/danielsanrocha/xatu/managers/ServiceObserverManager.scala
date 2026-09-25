package com.danielsanrocha.xatu.managers

import com.typesafe.scalalogging.Logger

import com.danielsanrocha.xatu.models.internals.Service
import com.danielsanrocha.xatu.observers.ServiceObserver
import com.danielsanrocha.xatu.repositories.StatusRepository
import com.danielsanrocha.xatu.services.ServiceService

class ServiceObserverManager(server: String, statusRepository: StatusRepository)(
    implicit val service: ServiceService,
    override implicit val ec: scala.concurrent.ExecutionContext
) extends Manager[Service, ServiceObserver](service, ec) {
  private val logging: Logger = Logger(this.getClass)
  logging.info("Starting ServiceObserverManager")

  override protected def createObserver(data: Service): ServiceObserver = {
    new ServiceObserver(data, server, statusRepository)
  }
}
