package com.danielsanrocha.xatu.commons

import com.danielsanrocha.xatu.UnitSpec

import java.util.concurrent.CopyOnWriteArrayList
import scala.concurrent.Future
import scala.jdk.CollectionConverters._

class LogBatcherSpec extends UnitSpec {
  describe("LogBatcher") {
    it("should send queued lines in batches of at most batchSize") {
      val sent = new CopyOnWriteArrayList[Seq[String]]()
      val batcher = new LogBatcher[String](capacity = 100, batchSize = 4, flushMillis = 100, send = b => { sent.add(b); 0 }).start()
      (1 to 10).foreach(i => batcher.offer(s"line $i") should equal(true))

      Future {
        Thread.sleep(600)
        batcher.close()
        sent.asScala.flatten.toSeq should equal((1 to 10).map(i => s"line $i"))
        all(sent.asScala.map(_.size)) should be <= 4
        batcher.droppedTotal should equal(0)
      }
    }

    it("should drop lines when the queue is full instead of blocking, and count them") {
      val gate = new Object
      val batcher = new LogBatcher[String](capacity = 5, batchSize = 10, flushMillis = 50, send = b => gate.synchronized { gate.wait(2000); 0 }).start()
      Future {
        Thread.sleep(150) // the sender is now blocked holding a batch, the queue fills behind it
        val accepted = (1 to 20).count(i => batcher.offer(s"line $i"))
        accepted should equal(5)
        batcher.droppedTotal should equal(15)
        gate.synchronized(gate.notifyAll())
        batcher.close()
        succeed
      }
    }

    it("should count lines the sender could not index and survive sender errors") {
      val calls = new java.util.concurrent.atomic.AtomicInteger(0)
      val batcher = new LogBatcher[String](capacity = 100, batchSize = 10, flushMillis = 50, send = b => if (calls.incrementAndGet() == 1) throw new Exception("es down") else 1).start()
      Future {
        batcher.offer("a"); Thread.sleep(200)
        batcher.offer("b"); Thread.sleep(200)
        batcher.close()
        batcher.failedTotal should equal(2) // one from the exception, one reported by the sender
      }
    }
  }
}
