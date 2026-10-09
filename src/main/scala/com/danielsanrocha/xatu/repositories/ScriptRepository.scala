package com.danielsanrocha.xatu.repositories

import com.danielsanrocha.xatu.models.internals.{NewScript, Script}

import scala.concurrent.Future

trait ScriptRepository {
  def getById(id: Long): Future[Option[Script]]
  def create(script: NewScript): Future[Long]
  def delete(id: Long): Future[Boolean]
  def update(id: Long, script: NewScript): Future[Long]
  def getAll(limit: Long, offset: Long): Future[Seq[Script]]
}
