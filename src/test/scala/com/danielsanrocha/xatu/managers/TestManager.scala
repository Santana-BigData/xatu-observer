package com.danielsanrocha.xatu.managers

import com.danielsanrocha.xatu.models.internals.Data
import com.danielsanrocha.xatu.observers.Observer
import com.danielsanrocha.xatu.services.Service

import scala.concurrent.ExecutionContext

trait TestManager {
  /** Data with state (status) that is not part of its configuration, like a Service row. */
  class StatefulData(id: Long, name: String, val directory: String, val status: Char) extends Data(id, name) {
    override def configuration: Any = (id, name, directory)
  }

  class MockObserver(d: Data) extends Observer[Data](d) {
    override val task: Runnable = () => {}

    var started = false

    override def start(): Unit = {
      started = true
    }

    var reloaded = false

    override def reload(d: Data): Unit = {
      _data = d
      reloaded = true
    }

    var stopped = false

    override def stop(): Unit = {
      stopped = true
    }
  }

  class MockManager(s: Service[Data], ec: ExecutionContext) extends Manager[Data, MockObserver](s, ec) {
    override def createObserver(data: Data): MockObserver = new MockObserver(data)
  }
}
