package com.danielsanrocha.xatu.repositories

import com.danielsanrocha.xatu.models.internals.{API, NewAPI}
import slick.jdbc.MySQLProfile.api._

import java.sql.Timestamp
import scala.concurrent.Future
import scala.language.postfixOps

class APIRepositoryImpl(implicit client: Database, implicit val ec: scala.concurrent.ExecutionContext) extends APIRepository {
  class APITable(tag: Tag) extends Table[API](tag, "tb_apis") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def host = column[String]("host")
    def port = column[Int]("port")
    def healthcheckRoute = column[String]("healthcheck_route")
    def status = column[Char]("status")
    def createDate = column[Timestamp]("create_date")
    def updateDate = column[Timestamp]("update_date")

    def * = (id, name, host, port, healthcheckRoute, status, createDate, updateDate) <> (API.tupled, API.unapply)
  }

  lazy val apis = TableQuery[APITable]

  override def getById(id: Long): Future[Option[API]] = {
    client.run(apis.filter(_.id === id).result.headOption)
  }

  override def create(api: NewAPI): Future[Long] = {
    client.run((apis.map(s => (s.name, s.host, s.port, s.healthcheckRoute)) returning apis.map(_.id)) += ((api.name, api.host, api.port, api.healthcheckRoute)))
  }

  override def delete(id: Long): Future[Boolean] = {
    client.run(apis.filter(_.id === id).delete)
  } map {
    case 0 => false
    case _ => true
  }

  override def update(id: Long, api: NewAPI): Future[Long] = {
    client.run(apis.filter(_.id === id).map(s => (s.name, s.host, s.port, s.healthcheckRoute)).update((api.name, api.host, api.port, api.healthcheckRoute))) map {
      _.toLong
    }
  }

  override def getAll(limit: Long, offset: Long): Future[Seq[API]] = {
    client.run(apis.take(limit).drop(offset).result)
  }
}
