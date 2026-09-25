package com.danielsanrocha.xatu.observers

import com.danielsanrocha.xatu.commons.FutureRetry
import com.danielsanrocha.xatu.models.internals.{API, CheckKind, ServerCheck}
import com.danielsanrocha.xatu.repositories.StatusRepository
import com.typesafe.scalalogging.Logger
import scalaj.http.{Http, HttpOptions}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, ExecutionContext}
import scala.language.postfixOps

/** Checks the api from this server; every Xatu checks it and saves its own result. */
class APIObserver(api: API, server: String, statusRepository: StatusRepository, implicit val ec: ExecutionContext) extends Observer[API](api) with FutureRetry {
  private val logging: Logger = Logger(this.getClass)

  private def save(status: Char, message: Option[String]): Unit =
    statusRepository.save(ServerCheck(CheckKind.API, _data.id, server, status, message, System.currentTimeMillis()))

  override lazy val task: Runnable = () => {
    val route = s"${_data.host}:${_data.port}${_data.healthcheckRoute}"
    logging.info(s"Making request to API(id, name) = (${api.id}, ${api.name}) route $route")
    try {
      val result = retry(ec, _ => Http(route).option(HttpOptions.connTimeout(10000)).option(HttpOptions.readTimeout(10000)).execute(), 5)
      val code = Await.result(result, FiniteDuration(5, TimeUnit.SECONDS)).code
      if (code == 200) {
        logging.debug(s"Healthcheck route at $route returned 200, setting status working...")
        save('W', None)
      } else {
        logging.debug(s"Healthcheck route at $route returned $code, setting status fail...")
        save('F', Some(s"HTTP $code"))
      }
    } catch {
      case e: Exception =>
        logging.error(s"Network problem with API(id = ${_data.id}, name = ${_data.name}) route $route. Exception: ${e.getMessage}")
        save('F', Option(e.getMessage))
    }
  }
}
