package com.danielsanrocha.xatu.repositories

import com.danielsanrocha.xatu.models.internals.{Log, LogContainer, LogService}

import scala.concurrent.Future
import scala.language.postfixOps

class LogRepositoryDummyImpl extends LogRepository {
  def createIndex(): Future[Unit] = {
    Future.successful()
  }

  override def create(documentId: String, log: LogService): Future[Unit] = {
    Future.successful()
  }

  override def create(documentId: String, log: LogContainer): Future[Unit] = {
    Future.successful()
  }

  override def search(query: String): Future[Seq[Log]] = {
    Future.successful(Seq())
  }

  override def status(): Future[Unit] = {
    Future.successful()
  }
}
