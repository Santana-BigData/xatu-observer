package com.danielsanrocha.xatu.observers

import com.twitter.util.logging.Logger

import java.nio.file.Paths
import scala.collection.mutable
import java.io.{BufferedReader, File, FileReader}
import java.util.concurrent._
import com.danielsanrocha.xatu.models.internals.{Service, Status, LogService => LogServiceModel, LogServiceObserverStatus}
import com.danielsanrocha.xatu.services.{LogService, ServiceService}

class LogServiceObserver(s: Service, implicit val service: ServiceService, implicit val logService: LogService) extends Observer[Service](s) {
  private val logging: Logger = Logger(this.getClass)

  private val files: mutable.Map[String, BufferedReader] = mutable.Map()

  override def status(): Status = { LogServiceObserverStatus(_data.id, _data.name, files.keys.toSeq) }

  override protected lazy val task: Runnable = () => {
    logging.debug(s"Searching for files Service(${_data.id}, ${_data.name})...")

    val regex = raw"${_data.logFileRegex}".r
    val directoryPath = new File(_data.logFileDirectory)
    try {
      directoryPath.list() foreach { filename =>
        if (regex matches filename) {
          logging.debug(s"File $filename i::match regex! Observing it...")

          val b: BufferedReader = files.get(filename) match {
            case Some(bufferedReader) =>
              logging.debug(s"already listening to file ${filename}")
              bufferedReader

            case None =>
              logging.debug(s"creating buffered reader to file ${filename}")

              val fullpath = Paths.get(_data.logFileDirectory, filename)
              val bufferedReader = new BufferedReader(new FileReader(fullpath.toFile))
              files.addOne(filename -> bufferedReader)
              var line: String = ""
              while (line != null) {
                line = bufferedReader.readLine()
              }
              bufferedReader
          }

          var line: String = ""
          while (line != null) {
            line = b.readLine()
            if (line != null) {
              logging.debug(s"Indexing log of Service(${_data.id}, ${_data.name}). Log: ${line}")

              logService.create(LogServiceModel(_data.id, _data.name, filename, line, System.currentTimeMillis()))
            }
          }
        } else {
          logging.debug(s"Filename $filename does not match the regex, skipping...")
        }
      }
    } catch {
      case e: Exception =>
        logging.warn(s"Error collecting logs. Message: ${e.getMessage}")
    }
  }

  override def reload(s: Service): Unit = {
    this._data = s
    files.foreach { case (filename, b) =>
      b.close()
      files.remove(filename)
    }
  }

  override def stop(): Unit = {
    interval.cancel(true)
    files.foreach { case (filename, b) =>
      b.close()
      files.remove(filename)
    }
  }
}
