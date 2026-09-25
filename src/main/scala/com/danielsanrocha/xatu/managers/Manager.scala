package com.danielsanrocha.xatu.managers

import com.danielsanrocha.xatu.models.internals.{Data, Status}
import com.danielsanrocha.xatu.observers.Observer
import com.danielsanrocha.xatu.services.Service
import com.typesafe.scalalogging.Logger

import java.util.concurrent.{ScheduledFuture, ScheduledThreadPoolExecutor, TimeUnit}
import scala.collection.mutable

abstract class Manager[DATA <: Data, OBSERVER <: Observer[DATA]](service: Service[DATA], implicit val ec: scala.concurrent.ExecutionContext) {
  private val logging: Logger = Logger(this.getClass)

  lazy val observers: mutable.Map[Long, OBSERVER] = mutable.Map()

  private val ex = new ScheduledThreadPoolExecutor(1)

  protected def createObserver(data: DATA): OBSERVER

  def status(): Seq[Status] = {
    val result = observers map { tuple =>
      tuple._2.status()
    }
    logging.debug(s"Returning status for ${observers.size} observers. Manager Status -> ${result}")
    result.toSeq
  }

  val task: Runnable = () => {
    service.getAll(1000, 0) map { objects =>
      objects.foreach { obj =>
        observers.get(obj.id) match {
          case Some(obs) =>
            logging.debug(s"Observer for '${obj.name}' already created")
            logging.debug(s"Checking for parameter changes DATA(${obj.id},${obj.name})")
            if (obj.configuration != obs.getData.configuration) {
              logging.info(s"Configuration of (${obj.id}, ${obj.name}) changed, reloading observer...")
              obs.reload(obj)
            } else {
              obs.refresh(obj)
            }
          case None =>
            logging.info(s"Creating observer for DATA with id ${obj.id} and name ${obj.name}")
            val observer = createObserver(obj)
            if (observer != null) {
              observers.addOne(obj.id -> observer)
              observer.start()
            }
        }
      }

      val idSet = objects.map(_.id)
      logging.debug(s"Ids on database ${idSet.mkString(",")}. Killing observers of deleted/stopped objects...")

      observers.foreach(tuple => {
        val id = tuple._1
        val obs = tuple._2

        idSet.find(_ == id) match {
          case None =>
            logging.info(s"Killing observer for DATA with id $id...")
            obs.stop()
            observers.remove(id)
          case Some(id) =>
            logging.debug(s"Keeping observer for id ${id}")
        }
      })
    }
  }

  var interval: ScheduledFuture[_] = null

  def start(): Unit = {
    interval = ex.scheduleAtFixedRate(task, 10, 10, TimeUnit.SECONDS)
  }
}
