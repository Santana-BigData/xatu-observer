package com.danielsanrocha.xatu.repositories

import com.danielsanrocha.xatu.models.internals.{NewService, Service}
import slick.jdbc.MySQLProfile.api._

import java.sql.Timestamp
import scala.concurrent.Future
import scala.language.postfixOps

class ServiceRepositoryImpl(implicit client: Database, implicit val ec: scala.concurrent.ExecutionContext) extends ServiceRepository {
  class ServiceTable(tag: Tag) extends Table[Service](tag, "tb_services") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def logFileDirectory = column[String]("log_file_directory")
    def logFileRegex = column[String]("log_file_regex")
    def pidFile = column[String]("pid_file")
    def status = column[Char]("status")
    def createDate = column[Timestamp]("create_date")
    def updateDate = column[Timestamp]("update_date")
    def * = (id, name, logFileDirectory, logFileRegex, pidFile, status, createDate, updateDate) <> (Service.tupled, Service.unapply)
  }

  lazy val services = TableQuery[ServiceTable]

  override def getById(id: Long): Future[Option[Service]] = {
    client.run(services.filter(_.id === id).result.headOption)
  }

  override def create(service: NewService): Future[Long] = {
    client.run(
      (services.map(s => (s.name, s.logFileDirectory, s.logFileRegex, s.pidFile)) returning services.map(_.id)) += ((service.name, service.logFileDirectory, service.logFileRegex, service.pidFile))
    ) map { x =>
      x.toLong
    }
  }

  override def delete(id: Long): Future[Boolean] = {
    client.run(services.filter(_.id === id).delete)
  } map {
    case 0 => false
    case _ => true
  }

  override def update(id: Long, service: NewService): Future[Long] = {
    client.run(services.filter(_.id === id).map(s => (s.name, s.logFileDirectory, s.logFileRegex, s.pidFile)).update(service.name, service.logFileDirectory, service.logFileRegex, service.pidFile))
  } map {
    _.toLong
  }

  override def getAll(limit: Long, offset: Long): Future[Seq[Service]] = {
    client.run(services.take(limit).drop(offset).result)
  }
}
