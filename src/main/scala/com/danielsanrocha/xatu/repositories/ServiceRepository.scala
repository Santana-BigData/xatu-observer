package com.danielsanrocha.xatu.repositories

import com.danielsanrocha.xatu.models.internals.{NewService, Service}

import scala.concurrent.Future

trait ServiceRepository {
  def getById(id: Long): Future[Option[Service]]
  def create(service: NewService): Future[Long]
  def delete(id: Long): Future[Boolean]
  def update(id: Long, service: NewService): Future[Long]
  def getAll(limit: Long, offset: Long): Future[Seq[Service]]
}
