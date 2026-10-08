package com.danielsanrocha.xatu.services

import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.models.internals.ApiToken
import com.danielsanrocha.xatu.repositories.ApiTokenRepository
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, anyBoolean, anyLong, anyString}
import org.mockito.Mockito.{never, times, verify, when}
import org.scalatestplus.mockito.MockitoSugar.mock

import java.sql.Timestamp
import scala.concurrent.Future

class ApiTokenServiceSpec extends UnitSpec {
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

  private def token(id: Long, readOnly: Boolean = true) =
    ApiToken(id, "grafana", "xatu_abcdef", "hash", readOnly, 1, new Timestamp(0), None, None)

  describe("generate") {
    it("should create random xatu_ tokens of 45 characters") {
      Future {
        val tokens = (1 to 1000).map(_ => ApiTokenService.generate())
        tokens.distinct.size should equal(1000)
        all(tokens.map(_.length)) should equal(45)
        all(tokens.map(ApiTokenService.looksLikeToken)) should equal(true)
      }
    }

    it("should hash with sha-256") {
      Future {
        ApiTokenService.hash("abc") should equal("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
      }
    }
  }

  describe("create") {
    it("should store only the hash and a prefix, and return the token once") {
      val repository = mock[ApiTokenRepository]
      when(repository.create(anyString(), anyString(), anyString(), anyBoolean(), anyLong())).thenReturn(Future.successful(7L))
      val service = new ApiTokenService(repository)

      service.create("grafana", readOnly = true, createdBy = 1) map { case (id, created) =>
        val prefix, hash = ArgumentCaptor.forClass(classOf[String])
        verify(repository).create(any(), prefix.capture(), hash.capture(), any(), any())
        id should equal(7L)
        prefix.getValue should equal(created.take(11))
        hash.getValue should equal(ApiTokenService.hash(created))
        hash.getValue should not include created
      }
    }
  }

  describe("authenticate") {
    it("should not query the database for things that are not api tokens") {
      val repository = mock[ApiTokenRepository]
      val service = new ApiTokenService(repository)
      service.authenticate("some-session-token") map { result =>
        verify(repository, never()).getActiveByHash(anyString())
        result should equal(None)
      }
    }

    it("should cache lookups and forget a token revoked on this server") {
      val repository = mock[ApiTokenRepository]
      val secret = ApiTokenService.generate()
      when(repository.getActiveByHash(ApiTokenService.hash(secret))).thenReturn(Future.successful(Some(token(3))))
      when(repository.touch(anyLong(), any())).thenReturn(Future.unit)
      when(repository.revoke(anyLong(), any())).thenReturn(Future.successful(true))
      val service = new ApiTokenService(repository)

      for {
        first <- service.authenticate(secret)
        second <- service.authenticate(secret)
        _ <- service.revoke(3)
        _ = when(repository.getActiveByHash(ApiTokenService.hash(secret))).thenReturn(Future.successful(None))
        afterRevoke <- service.authenticate(secret)
      } yield {
        first.map(_.id) should equal(Some(3))
        second.map(_.id) should equal(Some(3))
        afterRevoke should equal(None)
        // once before the revoke (cached), once after
        verify(repository, times(2)).getActiveByHash(ApiTokenService.hash(secret))
        // last use saved once, not on every request
        verify(repository, times(1)).touch(anyLong(), any())
        succeed
      }
    }
  }
}
