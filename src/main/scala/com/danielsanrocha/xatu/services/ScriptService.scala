package com.danielsanrocha.xatu.services

import com.danielsanrocha.xatu.models.internals.{NewScript, Script}

import scala.concurrent.Future

trait ScriptService extends Service[Script] {
  def getById(id: Long): Future[Option[Script]]
  def create(script: NewScript): Future[Long]
  def delete(id: Long): Future[Boolean]
  def update(id: Long, script: NewScript): Future[Boolean]
}
