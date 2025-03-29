package com.danielsanrocha.xatu.controllers

import com.danielsanrocha.xatu.models.internals.RequestId
import com.danielsanrocha.xatu.models.responses.ServerStatus
import com.danielsanrocha.xatu.repositories.LogRepository
import com.danielsanrocha.xatu.commons.FutureTimeout._
import com.github.dockerjava.api.DockerClient
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.http.Request
import com.twitter.finatra.http.Controller
import com.typesafe.scalalogging.Logger
import io.jvm.uuid.UUID
import redis.clients.jedis.JedisPool
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

class HealthcheckController(
    implicit val cachePool: JedisPool,
    implicit val client: Database,
    implicit val dockerClient: DockerClient,
    implicit val logRepository: LogRepository,
    implicit val ec: scala.concurrent.ExecutionContext,
    implicit val timeout: FiniteDuration
) extends Controller {
  private val logging: Logger = Logger(this.getClass)

  get("/api/healthcheck") { _: Request =>
    val requestId = Contexts.local.get(RequestId).head.requestId
    logging.info(s"(x-request-id - $requestId) Healthcheck called, checking redis, docker, elasticsearch and mysql...")

    val redisFuture = Future[Option[Throwable]] {
      val random = UUID.random.toString
      val cache = cachePool.getResource

      try {
        cache.set("xatu::random", random)
        val random2 = cache.get("xatu::random")
        cache.expire("xatu::random", 5)
        if (random != random2) throw new Exception("Problems with redis...")
        cache.close()
      } catch {
        case e: Throwable =>
          cache.close()
          throw e
      }
      None
    } recover { case e: Throwable =>
      logging.error(s"Error accessing Redis! Message: ${e.getMessage}")
      Some(e)
    }

    val mysqlFuture = client.run(sql"show tables".as[String]) map { _ =>
      None
    } withTimeout (ec, timeout) recover { case e: Throwable =>
      logging.error(s"Error accessing mysql! Message: ${e.getMessage}")
      Some(e)
    }

    val dockerFuture = Future[Option[Throwable]] {
      dockerClient.pingCmd().exec()
      None
    } withTimeout (ec, timeout) recover { case e: Throwable =>
      logging.error(s"Error accessing Docker! Message: ${e.getMessage}")
      Some(e)
    }

    val elasticsearchFuture = logRepository.status() map { _ =>
      None
    } withTimeout (ec, timeout) recover { case e: Throwable =>
      logging.error(s"Error accessing ElasticSearch! Message: ${e.getMessage}")
      Some(e)
    }

    Future.sequence(Seq(redisFuture, mysqlFuture, dockerFuture, elasticsearchFuture)) map { result =>
      var flag = false

      val redis = result.head match {
        case Some(e) => flag = true; e.getMessage
        case None    => "Ok"
      }
      val mysql = result(1) match {
        case Some(e) => flag = true; e.getMessage
        case None    => "Ok"
      }
      val docker = result(2) match {
        case Some(e) => flag = true; e.getMessage
        case None    => "Ok"
      }
      val elasticsearch = result(3) match {
        case Some(e) => flag = true; e.getMessage
        case None    => "Ok"
      }

      val status = ServerStatus(redis, mysql, docker, elasticsearch)
      if (flag) response.internalServerError(status)
      else response.ok(status)
    }
  }
}
