package com.danielsanrocha.xatu.commons

import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.commons.CassandraMigrator.Migration

import scala.concurrent.Future
import scala.io.Source

class CassandraMigratorSpec extends UnitSpec {
  describe("parseMigrations") {
    it("should keep only migration files, sorted by version") {
      Future {
        CassandraMigrator.parseMigrations(Seq("V010__later.cql", "README.md", "V002__second.cql", "V001__first.cql")) should equal(
          Seq(Migration(1, "first", "V001__first.cql"), Migration(2, "second", "V002__second.cql"), Migration(10, "later", "V010__later.cql"))
        )
      }
    }
  }

  describe("render") {
    it("should replace placeholders") {
      Future {
        CassandraMigrator.render("CREATE KEYSPACE ${keyspace} rf ${replication_factor}", Map("keyspace" -> "xatu", "replication_factor" -> "3")) should equal(
          "CREATE KEYSPACE xatu rf 3"
        )
      }
    }

    it("should fail on unknown placeholders") {
      Future {
        an[IllegalArgumentException] should be thrownBy CassandraMigrator.render("SELECT ${keyspce}", Map("keyspace" -> "xatu"))
      }
    }
  }

  describe("statements") {
    it("should split statements and drop comments") {
      Future {
        CassandraMigrator.statements("-- header\nCREATE TABLE a (x int); -- trailing\n\n-- other\nCREATE TABLE b (y int);\n") should equal(
          Seq("CREATE TABLE a (x int)", "CREATE TABLE b (y int)")
        )
      }
    }
  }

  describe("V001__metrics.cql") {
    it("should render into the keyspace, tables and 7 days ttl") {
      Future {
        val cql = Source.fromResource("cassandra/V001__metrics.cql").mkString
        val statements = CassandraMigrator.statements(CassandraMigrator.render(cql, Map("keyspace" -> "xatu", "replication_factor" -> "3")))

        statements.map(_.linesIterator.next()) should equal(
          Seq(
            "CREATE KEYSPACE IF NOT EXISTS xatu",
            "CREATE TABLE IF NOT EXISTS xatu.schema_version (",
            "CREATE TABLE IF NOT EXISTS xatu.metrics_by_server (",
            "CREATE TABLE IF NOT EXISTS xatu.metrics_servers ("
          )
        )
        statements(2) should include("default_time_to_live = 604800")
        statements(3) should include("default_time_to_live = 604800")
      }
    }
  }
}
