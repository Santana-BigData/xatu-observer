package com.danielsanrocha.xatu.services

import com.danielsanrocha.xatu.models.internals.ApiToken
import com.danielsanrocha.xatu.repositories.ApiTokenRepository
import com.typesafe.scalalogging.Logger

import java.security.{MessageDigest, SecureRandom}
import java.sql.Timestamp
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

/**
 * Permanent api tokens ("Authorization: Bearer xatu_..."). Tokens are random, only their
 * sha-256 is stored, and they are shown once, when created.
 *
 * Lookups are cached in memory for `cacheMillis`, so a revoked token keeps working on
 * the other servers for at most that long.
 */
class ApiTokenService(repository: ApiTokenRepository, cacheMillis: Long = 60000)(implicit val ec: ExecutionContext) {
  import ApiTokenService._

  private val logging: Logger = Logger(this.getClass)

  private case class Cached(token: Option[ApiToken], until: Long)
  private val cache = TrieMap.empty[String, Cached]
  private val lastTouch = TrieMap.empty[Long, Long]

  /** Creates a token and returns it with its id: the only time the token is available. */
  def create(name: String, readOnly: Boolean, createdBy: Long): Future[(Long, String)] = {
    val token = generate()
    repository.create(name, token.take(PrefixLength), hash(token), readOnly, createdBy).map(id => (id, token))
  }

  def list(): Future[Seq[ApiToken]] = repository.getAll()

  def revoke(id: Long): Future[Boolean] =
    repository.revoke(id, new Timestamp(System.currentTimeMillis())).map { revoked =>
      // this server stops accepting it now, the others when their cache expires
      if (revoked) cache.filterInPlace { case (_, c) => !c.token.exists(_.id == id) }
      revoked
    }

  /** The active token for this secret, if any. */
  def authenticate(token: String): Future[Option[ApiToken]] =
    if (!looksLikeToken(token)) Future.successful(None)
    else {
      val key = hash(token)
      val now = System.currentTimeMillis()
      val found = cache.get(key).filter(_.until > now) match {
        case Some(c) => Future.successful(c.token)
        case None =>
          repository.getActiveByHash(key).map { t =>
            cache.put(key, Cached(t, now + cacheMillis))
            t
          }
      }
      found.map { t =>
        t.foreach(touch)
        t
      }
    }

  /** Saves the last use, at most once a minute per token. */
  private def touch(t: ApiToken): Unit = {
    val now = System.currentTimeMillis()
    if (lastTouch.get(t.id).forall(now - _ >= 60000)) {
      lastTouch.put(t.id, now)
      repository.touch(t.id, new Timestamp(now)).failed.foreach(e => logging.warn(s"Could not save last use of api token ${t.id}: ${e.getMessage}"))
    }
  }
}

object ApiTokenService {
  val Prefix = "xatu_"
  // prefix shown in the list to identify a token: "xatu_" + 6 chars
  val PrefixLength = 11
  private val Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
  private val random = new SecureRandom()

  /** "xatu_" + 40 random base62 chars (~238 bits). */
  def generate(): String = Prefix + (1 to 40).map(_ => Alphabet.charAt(random.nextInt(Alphabet.length))).mkString

  def hash(token: String): String =
    MessageDigest.getInstance("SHA-256").digest(token.getBytes("UTF-8")).map("%02x".format(_)).mkString

  def looksLikeToken(token: String): Boolean = token.startsWith(Prefix) && token.length == Prefix.length + 40
}
