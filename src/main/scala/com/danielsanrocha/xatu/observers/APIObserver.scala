package com.danielsanrocha.xatu.observers

import com.danielsanrocha.xatu.commons.FutureRetry
import com.danielsanrocha.xatu.models.internals.API
import com.danielsanrocha.xatu.services.APIService
import com.typesafe.scalalogging.Logger
import scalaj.http.{Http, HttpOptions}

import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

class APIObserver(api: API, implicit val service: APIService, implicit val ec: ExecutionContext) extends Observer[API](api) with FutureRetry {
  private val logging: Logger = Logger(this.getClass)

  override lazy val task: Runnable = () => {
    val route = s"${_data.host}:${_data.port}${_data.healthcheckRoute}"
    logging.info(s"Making request to API(id, name) = (${api.id}, ${api.name}) route $route")
    try {
      val result = retry(ec, _ => Http(route).option(HttpOptions.connTimeout(10000)).option(HttpOptions.readTimeout(10000)).execute(), 5)
      result map {
        _.code match {
          case 200 =>
            logging.debug(s"Healthcheck route at $route returned 200, setting status working...")
            service.setStatus(_data.id, 'W')
          case _ =>
            logging.debug(s"Healthcheck route at $route returned not 200, setting status fail...")
            service.setStatus(_data.id, 'F')
        }
      } flatten
    } catch {
      case e: Exception =>
        logging.error(s"Network problem with API(id = ${_data.id}, name = ${_data.name}) route $route. Exception: ${e.getMessage}")
        logging.debug(s"Setting status to failed API(id = ${_data.id}, name = ${_data.name})")
        service.setStatus(_data.id, 'F')
    }
  }
}
