package com.danielsanrocha.xatu.controllers

import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.commons.AlertMetricSeries
import com.danielsanrocha.xatu.models.internals.{AlertRule, MetricsServer, NewAlertRule, SensorKind, SensorReading, TimedCredential}
import com.danielsanrocha.xatu.repositories.{AlertRuleRepository, GpuMetricsRepository, MetricsRepository, SensorsRepository}
import com.danielsanrocha.xatu.services.AlertRuleService
import com.twitter.finagle.http.Status
import org.mockito.ArgumentMatchers.{any, anyLong, anyString}
import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar.mock

import java.sql.Timestamp
import scala.concurrent.Future

class AlertRuleControllerSpec extends UnitSpec with TestController {
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
  private val credential = TimedCredential(5, "admin@mail.com", 0)
  private val minute = 60000L

  // s1: core 0 hot for 10 minutes, core 1 at 60°C; s2: no sensors
  private def defaultReadings(now: Long): Seq[SensorReading] =
    (0 until 10).flatMap { i =>
      val at = now - (10 - i) * minute
      Seq(SensorReading("s1", at, "cpu0 core 0", SensorKind.CpuTemp, 85, Some(87), None), SensorReading("s1", at, "cpu0 core 1", SensorKind.CpuTemp, 60, Some(87), None))
    }

  private def server(rules: Seq[AlertRule] = Seq(), readings: Long => Seq[SensorReading] = defaultReadings) = {
    val repository = mock[AlertRuleRepository]
    val metrics = mock[MetricsRepository]
    val sensors = mock[SensorsRepository]
    val now = System.currentTimeMillis()
    when(repository.getAll()).thenReturn(Future.successful(rules))
    when(repository.create(any[NewAlertRule]())).thenReturn(Future.successful(4L))
    when(repository.update(anyLong(), any[NewAlertRule]())).thenReturn(Future.successful(true))
    when(metrics.servers()).thenReturn(Future.successful(Seq(MetricsServer("s1", now, 8, 1), MetricsServer("s2", now, 8, 1))))
    when(sensors.byServer(org.mockito.ArgumentMatchers.eq("s1"), anyLong(), anyLong())).thenReturn(Future.successful(readings(now)))
    when(sensors.byServer(org.mockito.ArgumentMatchers.eq("s2"), anyLong(), anyLong())).thenReturn(Future.successful(Seq()))
    val service = new AlertRuleService(repository, new AlertMetricSeries(metrics, mock[GpuMetricsRepository], sensors), 60)
    createServer(new AlertRuleController(service, 60), credential)
  }

  private val body = """{"name": "cpu hot", "metric": "cpu_temp", "server": "", "operator": "above", "threshold": 80, "duration_minutes": 5, "enabled": true}"""

  describe("alert rule routes") {
    it("should list the metrics") {
      Future {
        ujson.read(server().httpGet("/api/alert-rules/metrics", andExpect = Status.Ok).contentString)("hits").arr.size should equal(7)
      }
    }

    it("should create and update a rule") {
      val s = server()
      Future {
        ujson.read(s.httpPost("/api/alert-rule", body, andExpect = Status.Ok).contentString)("id").num should equal(4)
        s.httpPut("/api/alert-rule/4", body, andExpect = Status.Ok)
        s.httpPost("/api/alert-rule", body.replace("cpu_temp", "nope"), andExpect = Status.BadRequest)
        succeed
      }
    }

    it("should preview a rule, with the state now and the last 24 hours") {
      Future {
        val json = ujson.read(server().httpPost("/api/alert-rules/preview", body, andExpect = Status.Ok).contentString)
        val states = json("servers").arr.map(s => s("server").str -> s("firing").bool).toMap
        states should equal(Map("s1" -> true, "s2" -> false))
        json("backtest").arr.map(b => b("server").str -> b("count").num).toMap should equal(Map("s1" -> 1.0, "s2" -> 0.0))
      }
    }

    it("should list rules with their state") {
      val rule = AlertRule(1, "cpu hot", "cpu_temp", "", "above", 80, 5, enabled = true, new Timestamp(0), new Timestamp(0))
      Future {
        val hit = ujson.read(server(Seq(rule)).httpGet("/api/alert-rules", andExpect = Status.Ok).contentString)("hits")(0)
        hit("firing").bool shouldBe true
      }
    }
  
    it("should fire when any core passes the value, even a different one each minute") {
      // the hot core changes every minute, none of them is hot for 5 minutes in a row
      val alternating = (now: Long) =>
        (0 until 10).flatMap { i =>
          val at = now - (10 - i) * minute
          Seq(
            SensorReading("s1", at, "cpu0 core 0", SensorKind.CpuTemp, if (i % 2 == 0) 85 else 60, None, None),
            SensorReading("s1", at, "cpu0 core 1", SensorKind.CpuTemp, if (i % 2 == 0) 60 else 85, None, None)
          )
        }
      Future {
        val json = ujson.read(server(readings = alternating).httpPost("/api/alert-rules/preview", body, andExpect = Status.Ok).contentString)
        json("servers").arr.find(_("server").str == "s1").get("firing").bool shouldBe true
      }
    }

    it("should fire a rule below the value when any sensor is below it") {
      // core 1 is at 60°C, below 70, while core 0 is at 85°C
      Future {
        val below = body.replace("\"above\"", "\"below\"").replace("80", "70")
        val json = ujson.read(server().httpPost("/api/alert-rules/preview", below, andExpect = Status.Ok).contentString)
        val s1 = json("servers").arr.find(_("server").str == "s1").get
        (s1("firing").bool, s1("current").num) should equal((true, 60.0))
      }
    }
  }
}
