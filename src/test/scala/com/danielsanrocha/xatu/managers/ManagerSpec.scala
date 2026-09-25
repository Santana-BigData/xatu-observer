package com.danielsanrocha.xatu.managers

import com.danielsanrocha.xatu.UnitSpec
import com.danielsanrocha.xatu.models.internals.Data
import com.danielsanrocha.xatu.services.Service
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{mock, times, verify, when}

import scala.concurrent.Future

class ManagerSpec extends UnitSpec with TestManager {
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

  describe("task method") {
    it("should create a new observer and start it ") {
      val service = mock(classOf[Service[Data]])
      val manager = new MockManager(service, ec)

      val data = new Data(10, "jujuba")
      when(service.getAll(any, any)).thenReturn(Future(Seq(data)))
      Future {
        manager.task.run()
        Thread.sleep(500)

        manager.observers.keySet should equal(Set(10))
        manager.observers.get(10).head.started should equal(true)
      }
    }

    it("should create new observers in the first run") {
      val service = mock(classOf[Service[Data]])
      val manager = new MockManager(service, ec)

      val data1 = new Data(1, "jujuba")
      val data2 = new Data(2, "aleluia")
      val data3 = new Data(3, "amoeba")
      when(service.getAll(any, any)).thenReturn(Future(Seq(data1, data2, data3)))

      Future {
        manager.task.run()
        Thread.sleep(1000)

        verify(service, times(1)).getAll(any, any)
        verify(service, times(1)).getAll(1000, 0)

        manager.observers.keySet should equal(Set(1, 2, 3))
        manager.observers.get(1).head.getData should equal(data1)
        manager.observers.get(2).head.getData should equal(data2)
        manager.observers.get(3).head.getData should equal(data3)
      }
    }

    it("should create new observers in the second run") {
      val service = mock(classOf[Service[Data]])
      val manager = new MockManager(service, ec)

      val data1 = new Data(3, "jujuba")
      val data2 = new Data(5, "aleluia")
      val data3 = new Data(8, "amoeba")
      when(service.getAll(any, any)).thenReturn(Future(Seq(data1, data2)))

      Future {
        manager.task.run()
        Thread.sleep(500)

        verify(service, times(1)).getAll(any, any)
        verify(service, times(1)).getAll(1000, 0)
        manager.observers.keySet should equal(Set(3, 5))
        manager.observers.get(3).head.getData should equal(data1)
        manager.observers.get(5).head.getData should equal(data2)

        when(service.getAll(any, any)).thenReturn(Future(Seq(data2, data1, data3)))
        manager.task.run()
        Thread.sleep(500)

        verify(service, times(2)).getAll(any, any)
        verify(service, times(2)).getAll(1000, 0)

        manager.observers.keySet should equal(Set(3, 5, 8))
        manager.observers.get(3).head.getData should equal(data1)
        manager.observers.get(5).head.getData should equal(data2)
        manager.observers.get(8).head.getData should equal(data3)
      }
    }

    it("should reload when DATA changes") {
      val service = mock(classOf[Service[Data]])
      val manager = new MockManager(service, ec)

      val data = new Data(id = 3, name = "jujuba")
      val cdata = new Data(id = 3, name = "aleluia")

      when(service.getAll(any, any)).thenReturn(Future(Seq(data)))

      Future {
        manager.task.run()
        Thread.sleep(500)

        verify(service, times(1)).getAll(any, any)
        verify(service, times(1)).getAll(1000, 0)
        manager.observers.keySet should equal(Set(3))
        manager.observers.get(3).head.getData should equal(data)

        when(service.getAll(any, any)).thenReturn(Future(Seq(cdata)))

        manager.task.run()
        Thread.sleep(500)

        verify(service, times(2)).getAll(any, any)
        verify(service, times(2)).getAll(1000, 0)
        manager.observers.keySet should equal(Set(3))
        manager.observers.get(3).head.getData should equal(cdata)
        manager.observers.get(3).head.reloaded should equal(true)
      }
    }

    it("should only refresh, not reload, when the state changes but the configuration does not") {
      val service = mock(classOf[Service[Data]])
      val manager = new MockManager(service, ec)

      val working = new StatefulData(3, "webmail", "/var/log/webmail", 'W')
      val failed = new StatefulData(3, "webmail", "/var/log/webmail", 'F')
      val moved = new StatefulData(3, "webmail", "/var/log/mail", 'F')

      when(service.getAll(any, any)).thenReturn(Future(Seq(working)))

      Future {
        manager.task.run()
        Thread.sleep(500)

        when(service.getAll(any, any)).thenReturn(Future(Seq(failed)))
        manager.task.run()
        Thread.sleep(500)

        val observer = manager.observers.get(3).head
        observer.reloaded should equal(false)
        observer.getData should equal(failed)

        when(service.getAll(any, any)).thenReturn(Future(Seq(moved)))
        manager.task.run()
        Thread.sleep(500)

        observer.reloaded should equal(true)
        observer.getData should equal(moved)
      }
    }

    it("should delete old observers") {
      val service = mock(classOf[Service[Data]])
      val manager = new MockManager(service, ec)

      val data1 = new Data(3, "jujuba")
      val data2 = new Data(5, "aleluia")
      val data3 = new Data(8, "amoeba")
      when(service.getAll(any, any)).thenReturn(Future(Seq(data1, data2, data3)))

      Future {
        manager.task.run()
        Thread.sleep(500)

        verify(service, times(1)).getAll(any, any)
        verify(service, times(1)).getAll(1000, 0)
        manager.observers.keySet should equal(Set(3, 8, 5))
        manager.observers.get(3).head.getData should equal(data1)
        manager.observers.get(5).head.getData should equal(data2)
        manager.observers.get(8).head.getData should equal(data3)
        val observer = manager.observers.get(3).head

        when(service.getAll(any, any)).thenReturn(Future(Seq(data3, data2)))
        manager.task.run()
        Thread.sleep(500)

        verify(service, times(2)).getAll(any, any)
        verify(service, times(2)).getAll(1000, 0)
        manager.observers.keySet should equal(Set(8, 5))
        manager.observers.get(5).head.getData should equal(data2)
        manager.observers.get(8).head.getData should equal(data3)
        observer.stopped should equal(true)
      }
    }
  }
}
