package com.danielsanrocha.xatu.repositories

import com.danielsanrocha.xatu.models.internals.{SentAlert, ServerCheck, XatuServerInfo}
import redis.clients.jedis.Jedis
import redis.clients.jedis.params.{ScanParams, SetParams}
import redis.clients.jedis.util.Pool

import scala.jdk.CollectionConverters._
import scala.util.Using

/** See StatusRepository for the keys. `ttlSeconds` is how long a check or heartbeat is valid. */
class StatusRepositoryImpl(pool: Pool[Jedis], ttlSeconds: Long) extends StatusRepository {
  import StatusRepositoryImpl._

  private def withRedis[T](f: Jedis => T): T = Using.resource(pool.getResource)(f)

  /** Keys matching a pattern, with SCAN (KEYS blocks redis). */
  private def scan(redis: Jedis, pattern: String): Seq[String] = {
    val params = new ScanParams().`match`(pattern).count(1000)
    val keys = Seq.newBuilder[String]
    var cursor = ScanParams.SCAN_POINTER_START
    do {
      val result = redis.scan(cursor, params)
      keys ++= result.getResult.asScala
      cursor = result.getCursor
    } while (cursor != ScanParams.SCAN_POINTER_START)
    keys.result().distinct
  }

  private def values(redis: Jedis, keys: Seq[String]): Seq[(String, String)] =
    if (keys.isEmpty) Seq()
    else keys.zip(redis.mget(keys: _*).asScala).collect { case (k, v) if v != null => (k, v) }

  override def save(check: ServerCheck): Unit = withRedis {
    _.setex(statusKey(check.kind, check.targetId, check.server), ttlSeconds, toJson(check))
  }

  override def checks(kind: String): Map[Long, Seq[ServerCheck]] = withRedis { redis =>
    values(redis, scan(redis, s"$Prefix::status::$kind::*"))
      .flatMap { case (_, json) => scala.util.Try(fromJson(json)).toOption }
      .groupBy(_.targetId)
  }

  override def heartbeat(server: String, at: Long): Unit = withRedis { redis =>
    redis.setex(s"$Prefix::server::$server", ttlSeconds, at.toString)
    redis.sadd(KnownServers, server)
  }

  override def servers(): Seq[XatuServerInfo] = withRedis { redis =>
    redis.smembers(KnownServers).asScala.toSeq.sorted.map { server =>
      val lastSeen = Option(redis.get(s"$Prefix::server::$server")).map(_.toLong)
      XatuServerInfo(server, lastSeen.isDefined, lastSeen)
    }
  }

  override def forgetServer(server: String): Unit = withRedis { redis =>
    redis.srem(KnownServers, server)
    redis.del(s"$Prefix::server::$server")
  }

  override def acquireLeadership(server: String, ttlMillis: Long): Boolean = withRedis { redis =>
    // renew if already mine, otherwise take it only if nobody has it (atomic in redis)
    val result = redis.eval(
      """if redis.call('get', KEYS[1]) == ARGV[1] then
        |  return redis.call('pexpire', KEYS[1], ARGV[2])
        |end
        |if redis.call('set', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then return 1 end
        |return 0""".stripMargin,
      List(Leader).asJava,
      List(server, ttlMillis.toString).asJava
    )
    result == 1L
  }

  override def leader(): Option[String] = withRedis(r => Option(r.get(Leader)))

  override def sentAlerts(): Map[String, SentAlert] = withRedis { redis =>
    values(redis, scan(redis, s"$Prefix::alert::*")).flatMap { case (k, v) =>
      scala.util.Try {
        val o = ujson.read(v)
        k.stripPrefix(s"$Prefix::alert::") -> SentAlert(o("at").num.toLong, o("message").str)
      }.toOption
    }.toMap
  }

  override def markAlertSent(key: String, alert: SentAlert): Unit = withRedis {
    // alerts of targets deleted meanwhile disappear after a week
    _.set(s"$Prefix::alert::$key", ujson.Obj("at" -> ujson.Num(alert.at.toDouble), "message" -> alert.message).render(), new SetParams().ex(7L * 24 * 3600))
  }

  override def clearAlert(key: String): Unit = withRedis(_.del(s"$Prefix::alert::$key"))
}

object StatusRepositoryImpl {
  val Prefix = "xatu"
  val KnownServers = s"$Prefix::servers::known"
  val Leader = s"$Prefix::leader"

  def statusKey(kind: String, id: Long, server: String): String = s"$Prefix::status::$kind::$id::$server"

  def toJson(c: ServerCheck): String =
    ujson
      .Obj(
        "kind" -> c.kind,
        "target_id" -> ujson.Num(c.targetId.toDouble),
        "server" -> c.server,
        "status" -> c.status.toString,
        "message" -> c.message.map(ujson.Str).getOrElse(ujson.Null),
        "checked_at" -> ujson.Num(c.checkedAt.toDouble)
      )
      .render()

  def fromJson(json: String): ServerCheck = {
    val o = ujson.read(json)
    ServerCheck(
      o("kind").str,
      o("target_id").num.toLong,
      o("server").str,
      o("status").str.head,
      o.obj.get("message").flatMap(_.strOpt),
      o("checked_at").num.toLong
    )
  }
}
