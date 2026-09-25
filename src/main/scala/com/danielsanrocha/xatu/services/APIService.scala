package com.danielsanrocha.xatu.services

import com.danielsanrocha.xatu.models.internals.{API, NewAPI}

import scala.concurrent.Future

trait APIService extends Service[API] {
  def getById(id: Long): Future[Option[API]]
  def create(service: NewAPI): Future[Long]
  def delete(id: Long): Future[Boolean]
  def update(id: Long, s: NewAPI): Future[Boolean]
}
