package com.danielsanrocha.xatu.commons

import com.danielsanrocha.xatu.UnitSpec
import com.typesafe.config.ConfigFactory
import redis.clients.jedis.{HostAndPort, JedisPool}

import scala.concurrent.Future

class RedisPoolSpec extends UnitSpec {
  describe("parseNodes method") {
    it("should parse a comma separated list of sentinels") {
      Future {
        RedisPool.parseNodes(" s1:26379, s2:26380 ,,s3:26381") should equal(
          Set(new HostAndPort("s1", 26379), new HostAndPort("s2", 26380), new HostAndPort("s3", 26381))
        )
      }
    }

    it("should return empty set for empty string") {
      Future { RedisPool.parseNodes("") shouldBe empty }
    }
  }

  describe("create method") {
    it("should create a JedisPool in standalone mode") {
      Future {
        val conf = ConfigFactory.parseString("""redis { mode = "standalone", host = "127.0.0.1", port = 6379 }""")
        val pool = RedisPool.create(conf)
        try pool shouldBe a[JedisPool]
        finally pool.close()
      }
    }

    it("should fail with invalid mode") {
      Future {
        val conf = ConfigFactory.parseString("""redis { mode = "cluster" }""")
        an[IllegalArgumentException] should be thrownBy RedisPool.create(conf)
      }
    }

    it("should fail in sentinel mode without nodes") {
      Future {
        val conf = ConfigFactory.parseString("""redis { mode = "sentinel", sentinel { master = "mymaster", nodes = "" } }""")
        an[IllegalArgumentException] should be thrownBy RedisPool.create(conf)
      }
    }
  }
}
