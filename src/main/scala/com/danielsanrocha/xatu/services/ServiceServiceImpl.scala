package com.danielsanrocha.xatu.services

import com.danielsanrocha.xatu.exceptions.NotFoundException
import com.danielsanrocha.xatu.models.internals.{NewService, Service}
import com.danielsanrocha.xatu.repositories.ServiceRepository
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future
import scala.language.postfixOps

class ServiceServiceImpl(implicit repository: ServiceRepository, implicit val ec: scala.concurrent.ExecutionContext) extends ServiceService {
  private val logging: Logger = Logger(this.getClass)

  override def getById(id: Long): Future[Option[Service]] = {
    repository.getById(id) map {
      case Some(service) => Some(service)
      case None          => None
    }
  }

  override def create(service: NewService): Future[Long] = {
    repository.create(service)
  }

  override def delete(id: Long): Future[Boolean] = {
    repository.delete(id)
  }

  override def update(id: Long, s: NewService): Future[Boolean] = {
    repository.getById(id) map {
      case None => throw new NotFoundException(s"Service with id $id not found")
      case Some(_) =>
        repository.update(id, s) map { _ =>
          true
        }
    } flatten
  }

  override def getAll(limit: Long, offset: Long): Future[Seq[Service]] = {
    repository.getAll(limit, offset)
  }

}
