package com.danielsanrocha.xatu.observers

import com.danielsanrocha.xatu.commons.ApiCheckState
import com.danielsanrocha.xatu.models.internals.{API, CheckKind, ServerCheck}
import com.danielsanrocha.xatu.repositories.StatusRepository
import com.typesafe.scalalogging.Logger
import scalaj.http.{Http, HttpOptions}

import scala.util.Try

/**
 * Checks the api from this server; every Xatu checks it and saves its own result.
 *
 * The request runs on the observer's own thread, never on the shared execution context:
 * when elasticsearch is slow that pool is full of log writes and a check waiting in its
 * queue used to time out and report the api as failing.
 */
class APIObserver(api: API, server: String, statusRepository: StatusRepository, failuresToFail: Int = 3, attempts: Int = 3)
    extends Observer[API](api) {
  private val logging: Logger = Logger(this.getClass)

  private var state = ApiCheckState(failuresToFail)

  /** HTTP status of the healthcheck route; throws on network errors. */
  protected def request(route: String): Int =
    Http(route).option(HttpOptions.connTimeout(5000)).option(HttpOptions.readTimeout(10000)).execute().code

  /** Right(code) or Left(error) after `attempts` tries. */
  private def check(route: String): Either[String, Int] = {
    var last: Either[String, Int] = Left("not tried")
    var attempt = 0
    while (attempt < attempts && (last.isLeft || last.exists(_ != 200))) {
      attempt += 1
      last = Try(request(route)).toEither.left.map(e => Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
    }
    last
  }

  override lazy val task: Runnable = () => {
    val route = s"${_data.host}:${_data.port}${_data.healthcheckRoute}"
    logging.debug(s"Making request to API(id, name) = (${_data.id}, ${_data.name}) route $route")

    val error = check(route) match {
      case Right(200)  => None
      case Right(code) => Some(s"HTTP $code")
      case Left(e)     => Some(e)
    }
    state = if (error.isEmpty) state.success else state.failure
    error.foreach(e => logging.warn(s"API(id = ${_data.id}, name = ${_data.name}) route $route failed (${state.consecutiveFailures}/$failuresToFail): $e"))

    Try(statusRepository.save(ServerCheck(CheckKind.API, _data.id, server, state.status, state.message(error), System.currentTimeMillis())))
      .failed
      .foreach(e => logging.error(s"Could not save the check of API ${_data.name}: ${e.getMessage}"))
  }
}
