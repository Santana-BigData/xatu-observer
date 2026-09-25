package com.danielsanrocha.xatu.observers

import com.danielsanrocha.xatu.models.internals.{LogServiceObserverStatus, Service, Status, LogService => LogServiceModel}
import com.danielsanrocha.xatu.services.{LogService, ServiceService}
import com.typesafe.scalalogging.Logger

import java.io.{BufferedReader, File, FileReader}
import java.nio.file.Paths
import scala.collection.mutable

class LogServiceObserver(s: Service, implicit val service: ServiceService, implicit val logService: LogService) extends Observer[Service](s) {
  private val logging: Logger = Logger(this.getClass)

  private val files: mutable.Map[String, BufferedReader] = mutable.Map()

  override def status(): Status = { LogServiceObserverStatus(_data.id, _data.name, files.keys.toSeq) }

  // task (observer thread) and reload/stop (manager thread) share the open files
  override lazy val task: Runnable = () => files.synchronized {
    logging.debug(s"Searching for files Service(${_data.id}, ${_data.name})...")

    val regex = raw"${_data.logFileRegex}".r
    val directoryPath = new File(_data.logFileDirectory)
    val fs = directoryPath.list()
    if (fs == null) {
      logging.warn(s"Directory ${_data.logFileDirectory} not found, skipping!")
    } else {
      try {
        fs foreach { filename =>
          if (regex matches filename) {
            logging.debug(s"File $filename match regex! Observing it...")

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
                logging.debug(s"Indexing log of Service(${_data.id}, ${_data.name}).")

                logService.create(LogServiceModel(_data.id, _data.name, filename, line, System.currentTimeMillis()))
              }
            }
          } else {
            logging.debug(s"Filename $filename does not match the regex, skipping...")
          }
        }
      } catch {
        case e: Exception =>
          logging.error(s"Error collecting logs. Message: ${e.getMessage}")
      }
    }
  }

  private def closeFiles(): Unit = files.synchronized {
    files.values.foreach(_.close())
    files.clear()
  }

  override def reload(s: Service): Unit = {
    this._data = s
    closeFiles()
  }

  override def stop(): Unit = {
    super.stop()
    closeFiles()
  }
}
