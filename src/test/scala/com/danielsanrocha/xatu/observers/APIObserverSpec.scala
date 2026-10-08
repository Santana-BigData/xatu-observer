package com.danielsanrocha.xatu.observers

import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.commons.ApiCheckState
import com.danielsanrocha.xatu.models.internals.{API, ServerCheck}
import com.danielsanrocha.xatu.repositories.StatusRepository
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.{atLeastOnce, verify}
import org.scalatestplus.mockito.MockitoSugar.mock

import java.sql.Timestamp
import scala.concurrent.Future
import scala.jdk.CollectionConverters._

class APIObserverSpec extends UnitSpec {
  private val api = API(3, "sysjud", "https://sysjud", 443, "/api", 'W', new Timestamp(0), new Timestamp(0))

  describe("ApiCheckState") {
    it("should fail only after the threshold and recover on one success") {
      Future {
        val s0 = ApiCheckState(3)
        s0.status should equal('W')
        val s2 = s0.failure.failure
        s2.status should equal('W')
        s2.message(Some("timeout")) should equal(Some("2/3 failed checks: timeout"))
        val s3 = s2.failure
        s3.status should equal('F')
        s3.message(Some("timeout")) should equal(Some("timeout"))
        s3.success.status should equal('W')
        s3.success.message(None) should equal(None)
      }
    }
  }

  describe("APIObserver") {
    class Fake(repository: StatusRepository, answers: Iterator[Either[String, Int]]) extends APIObserver(api, "s1", repository, failuresToFail = 3, attempts = 2) {
      var requests = 0
      override protected def request(route: String): Int = {
        requests += 1
        answers.next() match {
          case Right(code) => code
          case Left(e)     => throw new Exception(e)
        }
      }
    }

    def statuses(repository: StatusRepository): Seq[(Char, Option[String])] = {
      val captor: ArgumentCaptor[ServerCheck] = ArgumentCaptor.forClass(classOf[ServerCheck])
      verify(repository, atLeastOnce()).save(captor.capture())
      captor.getAllValues.asScala.toSeq.map(c => (c.status, c.message))
    }

    it("should retry within a check and stay working until three checks in a row fail") {
      val repository = mock[StatusRepository]
      // check 1: fail then 200 (retry saves it); checks 2-4: both attempts fail; check 5: 200
      val observer = new Fake(repository, Iterator(Left("timeout"), Right(200), Left("t"), Left("t"), Left("t"), Left("t"), Right(502), Right(502), Right(200)))
      Future {
        (1 to 5).foreach(_ => observer.task.run())
        statuses(repository) should equal(
          Seq(('W', None), ('W', Some("1/3 failed checks: t")), ('W', Some("2/3 failed checks: t")), ('F', Some("HTTP 502")), ('W', None))
        )
        observer.requests should equal(9)
      }
    }
  }
}
