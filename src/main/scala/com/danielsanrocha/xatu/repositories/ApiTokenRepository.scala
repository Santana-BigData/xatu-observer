package com.danielsanrocha.xatu.repositories

import com.danielsanrocha.xatu.models.internals.ApiToken
import slick.jdbc.MySQLProfile.api._

import java.sql.Timestamp
import scala.concurrent.{ExecutionContext, Future}

trait ApiTokenRepository {
  def create(name: String, prefix: String, hash: String, readOnly: Boolean, createdBy: Long): Future[Long]
  def getAll(): Future[Seq[ApiToken]]
  /** Active (not revoked) token with this hash. */
  def getActiveByHash(hash: String): Future[Option[ApiToken]]
  def revoke(id: Long, at: Timestamp): Future[Boolean]
  def touch(id: Long, at: Timestamp): Future[Unit]
}

class ApiTokenRepositoryImpl(implicit client: Database, implicit val ec: ExecutionContext) extends ApiTokenRepository {

  class ApiTokenTable(tag: Tag) extends Table[ApiToken](tag, "tb_api_tokens") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def tokenPrefix = column[String]("token_prefix")
    def tokenHash = column[String]("token_hash")
    def readOnly = column[Boolean]("read_only")
    def createdBy = column[Long]("created_by")
    def createDate = column[Timestamp]("create_date")
    def lastUsed = column[Option[Timestamp]]("last_used")
    def revokedDate = column[Option[Timestamp]]("revoked_date")

    def * = (id, name, tokenPrefix, tokenHash, readOnly, createdBy, createDate, lastUsed, revokedDate) <> (ApiToken.tupled, ApiToken.unapply)
  }

  lazy val tokens = TableQuery[ApiTokenTable]

  override def create(name: String, prefix: String, hash: String, readOnly: Boolean, createdBy: Long): Future[Long] =
    client.run(
      (tokens returning tokens.map(_.id)) +=
        ApiToken(0, name, prefix, hash, readOnly, createdBy, new Timestamp(System.currentTimeMillis()), None, None)
    )

  override def getAll(): Future[Seq[ApiToken]] = client.run(tokens.sortBy(_.id.desc).result)

  override def getActiveByHash(hash: String): Future[Option[ApiToken]] =
    client.run(tokens.filter(t => t.tokenHash === hash && t.revokedDate.isEmpty).result.headOption)

  override def revoke(id: Long, at: Timestamp): Future[Boolean] =
    client.run(tokens.filter(t => t.id === id && t.revokedDate.isEmpty).map(_.revokedDate).update(Some(at))).map(_ > 0)

  override def touch(id: Long, at: Timestamp): Future[Unit] =
    client.run(tokens.filter(_.id === id).map(_.lastUsed).update(Some(at))).map(_ => ())
}
