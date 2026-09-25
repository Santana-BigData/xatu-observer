package com.danielsanrocha.xatu.repositories

import com.danielsanrocha.xatu.models.internals.{Log, LogContainer, LogService}

import scala.concurrent.Future

trait LogRepository {
  def createIndex(): Future[Unit]
  def create(documentId: String, log: LogService): Future[Unit]
  def create(documentId: String, log: LogContainer): Future[Unit]
  def search(query: String, size: Option[Int]): Future[Seq[Log]]
  def status(): Future[Unit]
}
