package com.danielsanrocha.xatu.services

import com.danielsanrocha.xatu.exceptions.{BadArgumentException, NotFoundException}
import com.danielsanrocha.xatu.models.internals.{NewScript, Script}
import com.danielsanrocha.xatu.repositories.ScriptRepository
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

class ScriptServiceImpl(implicit repository: ScriptRepository, implicit val ec: scala.concurrent.ExecutionContext) extends ScriptService {
  private val logging: Logger = Logger(this.getClass)

  private def validated[T](s: NewScript)(f: => Future[T]): Future[T] =
    NewScript.validate(s) match {
      case Some(error) => Future.failed(new BadArgumentException(error))
      case None        => f
    }

  override def getById(id: Long): Future[Option[Script]] = repository.getById(id)

  override def create(s: NewScript): Future[Long] = validated(s)(repository.create(s))

  override def delete(id: Long): Future[Boolean] = repository.delete(id)

  override def update(id: Long, s: NewScript): Future[Boolean] = validated(s) {
    repository.getById(id) flatMap {
      case None    => Future.failed(new NotFoundException(s"Script with id $id not found"))
      case Some(_) => repository.update(id, s).map(_ => true)
    }
  }

  override def getAll(limit: Long, offset: Long): Future[Seq[Script]] = {
    logging.debug(s"Getting all scripts with limit $limit and offset $offset")
    repository.getAll(limit, offset)
  }
}
