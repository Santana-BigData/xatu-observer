package com.danielsanrocha.xatu.commons

import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.commons.StatusRules.{Problem, Target}
import com.danielsanrocha.xatu.models.internals.{CheckKind, SentAlert, ServerCheck, XatuServerInfo}

import scala.concurrent.Future

class StatusRulesSpec extends UnitSpec {
  private def check(kind: String, id: Long, server: String, status: Char, message: Option[String] = None) =
    ServerCheck(kind, id, server, status, message, 0)

  describe("aggregate") {
    it("should be unknown when no server checked it") {
      Future { StatusRules.aggregate(CheckKind.Service, Seq()) should equal('U') }
    }

    it("should fail a service or container when any server fails") {
      Future {
        val checks = Seq(check("service", 1, "s1", 'W'), check("service", 1, "s2", 'F'))
        StatusRules.aggregate(CheckKind.Service, checks) should equal('F')
        StatusRules.aggregate(CheckKind.Container, checks) should equal('F')
        StatusRules.aggregate(CheckKind.Service, checks.take(1)) should equal('W')
      }
    }

    it("should fail an api only when most servers fail") {
      Future {
        val w = (s: String) => check("api", 1, s, 'W')
        val f = (s: String) => check("api", 1, s, 'F')
        StatusRules.aggregate(CheckKind.API, Seq(w("s1"), w("s2"), w("s3"), f("s4"))) should equal('W')
        StatusRules.aggregate(CheckKind.API, Seq(w("s1"), w("s2"), f("s3"), f("s4"))) should equal('W')
        StatusRules.aggregate(CheckKind.API, Seq(w("s1"), f("s2"), f("s3"), f("s4"))) should equal('F')
        StatusRules.aggregate(CheckKind.API, Seq(f("s1"))) should equal('F')
      }
    }
  }

  describe("problems") {
    val targets = Seq(Target("service", 34, "webmail"), Target("api", 3, "sysjud"), Target("container", 7, "nginx"))
    val alive = Seq(XatuServerInfo("s1", alive = true, Some(1)), XatuServerInfo("s2", alive = true, Some(1)))

    it("should name the server where a service or container is failing") {
      Future {
        val checks = Map(
          "service" -> Map(34L -> Seq(check("service", 34, "s1", 'W'), check("service", 34, "s2", 'F', Some("failed")))),
          "container" -> Map(7L -> Seq(check("container", 7, "s1", 'F', Some("Exited (1) 2 minutes ago"))))
        )
        StatusRules.problems(targets, checks, alive) should equal(
          Seq(
            Problem("service::34::s2", "Service webmail is not running on s2 (failed)"),
            Problem("container::7::s1", "Container nginx is not running on s1 (Exited (1) 2 minutes ago)")
          )
        )
      }
    }

    it("should alert an api only when most servers fail, listing them") {
      Future {
        val one = Map("api" -> Map(3L -> Seq(check("api", 3, "s1", 'W'), check("api", 3, "s2", 'W'), check("api", 3, "s3", 'F'))))
        StatusRules.problems(targets, one, alive) shouldBe empty

        val most = Map("api" -> Map(3L -> Seq(check("api", 3, "s1", 'W'), check("api", 3, "s2", 'F', Some("HTTP 502")), check("api", 3, "s3", 'F'))))
        StatusRules.problems(targets, most, alive) should equal(
          Seq(Problem("api::3", "API sysjud is broken, failing on 2/3 servers: s2 (HTTP 502), s3"))
        )
      }
    }

    it("should alert servers that stopped reporting") {
      Future {
        val servers = alive :+ XatuServerInfo("s4", alive = false, None)
        StatusRules.problems(targets, Map(), servers).map(_.key) should equal(Seq("server::s4"))
      }
    }
  }

  describe("plan") {
    val minute = 60 * 1000L
    val p1 = Problem("service::34::s2", "webmail down")
    val p2 = Problem("api::3", "sysjud down")

    it("should send new problems and repeat old ones after the repeat time") {
      Future {
        val sent = Map("service::34::s2" -> SentAlert(0, "webmail down"), "api::3" -> SentAlert(25 * minute, "sysjud down"))
        StatusRules.plan(Seq(p1, p2), sent, 30 * minute, 30 * minute).send should equal(Seq(p1))
        StatusRules.plan(Seq(p1, p2), Map(), 0, 30 * minute).send should equal(Seq(p1, p2))
      }
    }

    it("should resolve alerts that are not a problem anymore") {
      Future {
        val sent = Map("service::34::s2" -> SentAlert(0, "webmail down"), "api::3" -> SentAlert(0, "sysjud down"))
        val plan = StatusRules.plan(Seq(p2), sent, minute, 30 * minute)
        plan.send shouldBe empty
        plan.resolved should equal(Seq("service::34::s2" -> SentAlert(0, "webmail down")))
      }
    }
  }
}
