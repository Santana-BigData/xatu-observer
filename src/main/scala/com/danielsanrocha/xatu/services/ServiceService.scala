package com.danielsanrocha.xatu.services

import com.danielsanrocha.xatu.models.internals.{NewService, Service => ServiceModel}

import scala.concurrent.Future

trait ServiceService extends Service[ServiceModel] {
  def getById(id: Long): Future[Option[ServiceModel]]
  def create(service: NewService): Future[Long]
  def delete(id: Long): Future[Boolean]
  def update(id: Long, s: NewService): Future[Boolean]
}
