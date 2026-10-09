package com.danielsanrocha.xatu.repositories

import com.danielsanrocha.xatu.models.internals.{AlertRule, NewAlertRule}
import slick.jdbc.MySQLProfile.api._

import java.sql.Timestamp
import scala.concurrent.Future

trait AlertRuleRepository {
  def getById(id: Long): Future[Option[AlertRule]]
  def create(rule: NewAlertRule): Future[Long]
  def delete(id: Long): Future[Boolean]
  def update(id: Long, rule: NewAlertRule): Future[Boolean]
  def getAll(): Future[Seq[AlertRule]]
}

class AlertRuleRepositoryImpl(implicit client: Database, implicit val ec: scala.concurrent.ExecutionContext) extends AlertRuleRepository {
  class AlertRuleTable(tag: Tag) extends Table[AlertRule](tag, "tb_alert_rules") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def metric = column[String]("metric")
    def server = column[String]("server")
    def operator = column[String]("operator")
    def threshold = column[Double]("threshold")
    def durationMinutes = column[Int]("duration_minutes")
    def enabled = column[Boolean]("enabled")
    def createDate = column[Timestamp]("create_date")
    def updateDate = column[Timestamp]("update_date")
    def * = (id, name, metric, server, operator, threshold, durationMinutes, enabled, createDate, updateDate) <> (AlertRule.tupled, AlertRule.unapply)
  }

  lazy val rules = TableQuery[AlertRuleTable]

  private def editable(t: AlertRuleTable) = (t.name, t.metric, t.server, t.operator, t.threshold, t.durationMinutes, t.enabled)

  private def values(r: NewAlertRule) = (r.name.trim, r.metric, r.server.trim, r.operator, r.threshold, r.durationMinutes, r.enabled)

  override def getById(id: Long): Future[Option[AlertRule]] = client.run(rules.filter(_.id === id).result.headOption)

  override def create(rule: NewAlertRule): Future[Long] = client.run((rules.map(editable) returning rules.map(_.id)) += values(rule))

  override def delete(id: Long): Future[Boolean] = client.run(rules.filter(_.id === id).delete).map(_ > 0)

  override def update(id: Long, rule: NewAlertRule): Future[Boolean] =
    client.run(rules.filter(_.id === id).map(editable).update(values(rule))).map(_ > 0)

  override def getAll(): Future[Seq[AlertRule]] = client.run(rules.sortBy(_.id).result)
}
