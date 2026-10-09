package com.danielsanrocha.xatu.repositories

import com.danielsanrocha.xatu.models.internals.{NewScript, Script}
import slick.jdbc.MySQLProfile.api._

import java.sql.Timestamp
import scala.concurrent.Future
import scala.language.postfixOps

class ScriptRepositoryImpl(implicit client: Database, implicit val ec: scala.concurrent.ExecutionContext) extends ScriptRepository {
  class ScriptTable(tag: Tag) extends Table[Script](tag, "tb_scripts") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def script = column[String]("script")
    def intervalSeconds = column[Int]("interval_seconds")
    def timeoutSeconds = column[Int]("timeout_seconds")
    def createDate = column[Timestamp]("create_date")
    def updateDate = column[Timestamp]("update_date")
    def * = (id, name, script, intervalSeconds, timeoutSeconds, createDate, updateDate) <> (Script.tupled, Script.unapply)
  }

  lazy val scripts = TableQuery[ScriptTable]

  private def editable(t: ScriptTable) = (t.name, t.script, t.intervalSeconds, t.timeoutSeconds)

  private def values(s: NewScript) = (s.name, s.script, s.intervalSeconds, s.timeoutSeconds)

  override def getById(id: Long): Future[Option[Script]] = {
    client.run(scripts.filter(_.id === id).result.headOption)
  }

  override def create(script: NewScript): Future[Long] = {
    client.run((scripts.map(editable) returning scripts.map(_.id)) += values(script))
  }

  override def delete(id: Long): Future[Boolean] = {
    client.run(scripts.filter(_.id === id).delete)
  } map {
    case 0 => false
    case _ => true
  }

  override def update(id: Long, script: NewScript): Future[Long] = {
    client.run(scripts.filter(_.id === id).map(editable).update(values(script))) map {
      _.toLong
    }
  }

  override def getAll(limit: Long, offset: Long): Future[Seq[Script]] = {
    client.run(scripts.sortBy(_.id).drop(offset).take(limit).result)
  }
}
