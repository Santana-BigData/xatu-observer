package com.danielsanrocha.xatu.repositories

import com.danielsanrocha.xatu.models.internals.{API, NewAPI}

import scala.concurrent.Future

trait APIRepository {
  def getById(id: Long): Future[Option[API]]
  def create(service: NewAPI): Future[Long]
  def delete(id: Long): Future[Boolean]
  def update(id: Long, service: NewAPI): Future[Long]
  def getAll(limit: Long, offset: Long): Future[Seq[API]]
}
