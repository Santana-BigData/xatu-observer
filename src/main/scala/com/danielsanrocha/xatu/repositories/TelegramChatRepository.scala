package com.danielsanrocha.xatu.repositories

import com.danielsanrocha.xatu.models.internals.{NewTelegramChat, TelegramChat}
import slick.jdbc.MySQLProfile.api._

import java.sql.Timestamp
import scala.concurrent.Future

trait TelegramChatRepository {
  def getAll(): Future[Seq[TelegramChat]]
  def getById(id: Long): Future[Option[TelegramChat]]
  def create(chat: NewTelegramChat): Future[Long]
  def update(id: Long, chat: NewTelegramChat): Future[Boolean]
  def delete(id: Long): Future[Boolean]
}

class TelegramChatRepositoryImpl(implicit client: Database, implicit val ec: scala.concurrent.ExecutionContext) extends TelegramChatRepository {
  class TelegramChatTable(tag: Tag) extends Table[TelegramChat](tag, "tb_telegram_chats") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def chatId = column[String]("chat_id")
    def enabled = column[Boolean]("enabled")
    def createDate = column[Timestamp]("create_date")
    def updateDate = column[Timestamp]("update_date")
    def * = (id, name, chatId, enabled, createDate, updateDate) <> (TelegramChat.tupled, TelegramChat.unapply)
  }

  lazy val chats = TableQuery[TelegramChatTable]

  private def values(c: NewTelegramChat) = (c.name.trim, c.chatId.trim, c.enabled)

  override def getAll(): Future[Seq[TelegramChat]] = client.run(chats.sortBy(_.id).result)

  override def getById(id: Long): Future[Option[TelegramChat]] = client.run(chats.filter(_.id === id).result.headOption)

  override def create(chat: NewTelegramChat): Future[Long] =
    client.run((chats.map(c => (c.name, c.chatId, c.enabled)) returning chats.map(_.id)) += values(chat))

  override def update(id: Long, chat: NewTelegramChat): Future[Boolean] =
    client.run(chats.filter(_.id === id).map(c => (c.name, c.chatId, c.enabled)).update(values(chat))).map(_ > 0)

  override def delete(id: Long): Future[Boolean] = client.run(chats.filter(_.id === id).delete).map(_ > 0)
}
