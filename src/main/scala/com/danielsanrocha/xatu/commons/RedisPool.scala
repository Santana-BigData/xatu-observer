package com.danielsanrocha.xatu.commons

import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import redis.clients.jedis.{DefaultJedisClientConfig, HostAndPort, Jedis, JedisPool, JedisPoolConfig, JedisSentinelPool}
import redis.clients.jedis.util.Pool

import scala.jdk.CollectionConverters._

object RedisPool {
  private val logging: Logger = Logger(this.getClass)

  private val timeout = 10000

  private def optionalString(conf: Config, path: String): Option[String] =
    if (conf.hasPath(path)) Some(conf.getString(path)).filter(_.nonEmpty) else None

  def parseNodes(nodes: String): Set[HostAndPort] =
    nodes.split(",").map(_.trim).filter(_.nonEmpty).map(HostAndPort.from).toSet

  private def poolConfig(): JedisPoolConfig = {
    val config = new JedisPoolConfig()
    config.setMaxTotal(400)
    config.setMaxIdle(400)
    config.setMinIdle(200)
    config.setMaxWait(java.time.Duration.parse("PT1S"))
    config.setBlockWhenExhausted(true)
    config.setTestOnBorrow(true)
    config.setTestOnReturn(true)
    config.setTestWhileIdle(true)
    config.setNumTestsPerEvictionRun(3)
    config
  }

  def create(conf: Config): Pool[Jedis] = {
    val password = optionalString(conf, "redis.password")

    conf.getString("redis.mode").toLowerCase match {
      case "sentinel" =>
        val master = conf.getString("redis.sentinel.master")
        val nodes = parseNodes(conf.getString("redis.sentinel.nodes"))
        if (nodes.isEmpty) throw new IllegalArgumentException("redis.sentinel.nodes (REDIS_SENTINEL_NODES) must have at least one host:port!")
        logging.info(s"Using Redis Sentinel with master '$master' and sentinels ${nodes.mkString(",")}...")

        val masterConfig = DefaultJedisClientConfig
          .builder()
          .timeoutMillis(timeout)
          .password(password.orNull)
          .build()
        val sentinelConfig = DefaultJedisClientConfig
          .builder()
          .timeoutMillis(timeout)
          .password(optionalString(conf, "redis.sentinel.password").orNull)
          .build()

        new JedisSentinelPool(master, nodes.asJava, poolConfig(), masterConfig, sentinelConfig)

      case "standalone" =>
        val host = conf.getString("redis.host")
        val port = conf.getInt("redis.port")
        logging.info(s"Using standalone Redis at $host:$port...")
        password match {
          case Some(p) => new JedisPool(poolConfig(), host, port, timeout, p)
          case None    => new JedisPool(poolConfig(), host, port, timeout)
        }

      case other => throw new IllegalArgumentException(s"Invalid redis.mode (REDIS_MODE) '$other', expected 'standalone' or 'sentinel'!")
    }
  }
}
