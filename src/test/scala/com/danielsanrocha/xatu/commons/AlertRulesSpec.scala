package com.danielsanrocha.xatu.commons

import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.commons.AlertRules.{Episode, Sample}
import com.danielsanrocha.xatu.models.internals.{AlertRule, NewAlertRule}

import java.sql.Timestamp
import scala.concurrent.Future

class AlertRulesSpec extends UnitSpec {
  private val minute = 60000L
  private val hot = AlertRule(1, "cpu quente", "cpu_temp", "", "above", 80, 5, enabled = true, new Timestamp(0), new Timestamp(0))
  private val slowFan = hot.copy(id = 2, name = "fan", metric = "fan_rpm", operator = "below", threshold = 1500, durationMinutes = 3)

  /** One sample per minute, from minute 0. */
  private def perMinute(values: Double*): Seq[Sample] = values.zipWithIndex.map { case (v, i) => Sample(i * minute, v) }

  describe("episodes") {
    it("should fire only when the condition lasts the duration") {
      Future {
        // 4 minutes above 80: not enough for 5
        AlertRules.episodes(perMinute(70, 81, 82, 85, 83, 70), hot, minute) shouldBe empty
        // 5 minutes above 80
        AlertRules.episodes(perMinute(70, 81, 82, 85, 83, 81, 70), hot, minute) should equal(Seq(Episode(minute, 5 * minute, 85, 81)))
      }
    }

    it("should break a run on a sample out of the condition or on missing data") {
      Future {
        AlertRules.episodes(perMinute(81, 82, 79, 81, 82, 83), hot, minute) shouldBe empty
        // 3 samples, 10 minutes without data, 3 samples: two runs, none of 5 minutes
        val gap = perMinute(81, 82, 83) ++ perMinute(81, 82, 83).map(s => s.copy(at = s.at + 13 * minute))
        AlertRules.episodes(gap, hot, minute) shouldBe empty
        // one missing sample does not break it
        val oneMissing = perMinute(81, 82, 83, 84, 85, 86).filterNot(_.at == 2 * minute)
        AlertRules.episodes(oneMissing, hot, minute).map(_.worst) should equal(Seq(86.0))
      }
    }

    it("should alert values below the threshold with the lowest as the worst") {
      Future {
        AlertRules.episodes(perMinute(4000, 1200, 0, 900, 4000), slowFan, minute) should equal(Seq(Episode(minute, 3 * minute, 0, 900)))
      }
    }
  }

  describe("state") {
    it("should fire while the condition lasts the duration up to the last sample") {
      Future {
        val samples = perMinute(70, 81, 82, 85, 83, 81, 84)
        val st = AlertRules.state("s1", samples, hot, 6 * minute + 10000, minute)
        (st.current, st.inConditionSince, st.worst, st.firing) should equal((Some(84.0), Some(minute), Some(85.0), true))
        AlertRules.message(hot, st, 6 * minute + 10000) should equal(
          "CPU temperature (any core) on s1 above 80°C for 5 min (now 84°C, worst 85°C) - rule 'cpu quente'"
        )
      }
    }

    it("should not fire after the condition ended, nor on old data") {
      Future {
        val ended = AlertRules.state("s1", perMinute(81, 82, 83, 84, 85, 86, 70), hot, 6 * minute, minute)
        (ended.current, ended.inConditionSince, ended.firing) should equal((Some(70.0), None, false))
        // the server stopped sending 10 minutes ago: unknown, not firing
        val old = AlertRules.state("s1", perMinute(81, 82, 83, 84, 85, 86), hot, 15 * minute, minute)
        (old.current, old.firing) should equal((None, false))
      }
    }

    it("should tell how long a server is in the condition before firing") {
      Future {
        val st = AlertRules.state("s1", perMinute(70, 81, 82), hot, 2 * minute, minute)
        (st.inConditionSince, st.firing) should equal((Some(minute), false))
      }
    }
  }

  describe("NewAlertRule.validate") {
    it("should accept a valid rule and reject unknown metrics, operators and durations") {
      Future {
        val ok = NewAlertRule("cpu quente", "cpu_temp", "", "above", 80, 5)
        NewAlertRule.validate(ok) shouldBe None
        NewAlertRule.validate(ok.copy(metric = "cpu_temperature")) shouldBe defined
        NewAlertRule.validate(ok.copy(operator = ">")) shouldBe defined
        NewAlertRule.validate(ok.copy(durationMinutes = 0)) shouldBe defined
        NewAlertRule.validate(ok.copy(threshold = Double.NaN)) shouldBe defined
        NewAlertRule.validate(ok.copy(name = " ")) shouldBe defined
      }
    }
  }
}
