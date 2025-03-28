package com.danielsanrocha.xatu.commons

import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success}

trait FutureRetry {
  private val logging: Logger = Logger(this.getClass)

  private def retry[A](implicit ec: ExecutionContext, func: Unit => A, retries: Int, attempt: Int): Future[A] = {
    Future { func() }
      .transform({
        case Success(x) => Success(Future.successful(x))
        case Failure(ex) =>
          logging.warn(s"Failed in retry number $attempt: [${ex.getClass.toString}] ${ex.getMessage}")
          if (attempt >= retries) {
            logging.warn(f"Max attempt reached: [${ex.getClass.toString}] ${ex.getMessage}")
            Failure(ex)
          } else {
            Success(retry(ec, func, retries, attempt + 1))
          }
      }) flatten
  }
  def retry[A](implicit ec: ExecutionContext, func: Unit => A, retries: Int): Future[A] = {
    retry(ec, func, retries, 0)
  }
}
