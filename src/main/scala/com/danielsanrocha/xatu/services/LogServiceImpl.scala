package com.danielsanrocha.xatu.services

import scala.concurrent.Future
import io.jvm.uuid.UUID

import com.danielsanrocha.xatu.repositories.LogRepository
import com.danielsanrocha.xatu.models.internals.{Log, LogContainer, LogService => LogServiceModel}

class LogServiceImpl(implicit val repository: LogRepository) extends LogService {
  override def create(log: LogServiceModel): Future[Unit] = {
    val uuid = UUID.random.toString
    val name = s"service-$uuid"

    repository.create(name, log)
  }

  override def create(log: LogContainer): Future[Unit] = {
    val uuid = UUID.random.toString
    val name = s"container-$uuid"

    repository.create(name, log)
  }

  override def search(query: String, size: Option[Int]): Future[Seq[Log]] = {
    repository.search(query, size)
  }
}
