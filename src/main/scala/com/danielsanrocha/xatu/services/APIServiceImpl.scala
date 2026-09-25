package com.danielsanrocha.xatu.services

import com.danielsanrocha.xatu.exceptions.NotFoundException
import com.danielsanrocha.xatu.models.internals.{NewAPI, API}
import com.danielsanrocha.xatu.repositories.APIRepository
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future
import scala.language.postfixOps

class APIServiceImpl(implicit repository: APIRepository, implicit val ec: scala.concurrent.ExecutionContext) extends APIService {
  private val logging: Logger = Logger(this.getClass)

  override def getById(id: Long): Future[Option[API]] = {
    repository.getById(id) map {
      case Some(api) => Some(api)
      case None      => None
    }
  }

  override def create(api: NewAPI): Future[Long] = {
    repository.create(api)
  }

  override def delete(id: Long): Future[Boolean] = {
    repository.delete(id)
  }

  override def update(id: Long, s: NewAPI): Future[Boolean] = {
    repository.getById(id) map {
      case None => throw new NotFoundException(s"API with id $id not found")
      case Some(_) =>
        repository.update(id, s) map { _ =>
          true
        }
    } flatten
  }

  override def getAll(limit: Long, offset: Long): Future[Seq[API]] = {
    logging.debug(s"Getting all APIS with limit $limit and offset $offset")
    repository.getAll(limit, offset)
  }

}
