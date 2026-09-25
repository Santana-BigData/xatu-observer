package com.danielsanrocha.xatu.observers

import com.danielsanrocha.xatu.models.internals.{Data, SimpleStatus, Status}
import com.typesafe.scalalogging.Logger

import java.util.concurrent.{ScheduledFuture, ScheduledThreadPoolExecutor, TimeUnit}

abstract class Observer[DATA <: Data](d: DATA) {
  private val logging: Logger = Logger(this.getClass)

  protected var _data: DATA = d

  def getData: DATA = _data

  def reload(data: DATA): Unit = {
    _data = data
  }

  /** Keeps the latest row (status, dates) without reloading anything. */
  def refresh(data: DATA): Unit = {
    _data = data
  }

  def status(): Status = {
    SimpleStatus(_data.id, _data.name)
  }

  def stop(): Unit = {
    interval match {
      case Some(inter) => inter.cancel(true)
      case None        => logging.warn(s"Trying to stop a already stopped observer")
    }
  }

  val task: Runnable

  private val ex = new ScheduledThreadPoolExecutor(1)

  var interval: Option[ScheduledFuture[_]] = None

  def start(): Unit = {
    logging.info(s"Starting Observer for Data with id ${_data.id} and name ${_data.name}")
    interval = Some(ex.scheduleAtFixedRate(task, 10, 10, TimeUnit.SECONDS))
  }
}
