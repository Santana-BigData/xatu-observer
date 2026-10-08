package com.danielsanrocha.xatu.commons

import com.typesafe.scalalogging.Logger

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ArrayBlockingQueue, TimeUnit}
import scala.collection.mutable.ArrayBuffer
import scala.util.Try

/**
 * Bounded queue of log documents sent in batches by one thread. Producers never block:
 * when the queue is full the line is dropped and counted (reported once a minute), so a
 * slow elasticsearch never holds the observers or the thread pool of the application.
 *
 * `send` receives a batch and returns how many documents it could not index.
 */
class LogBatcher[A](capacity: Int, batchSize: Int, flushMillis: Long, send: Seq[A] => Int, name: String = "LogBatcher") {
  private val logging: Logger = Logger(this.getClass)

  private val queue = new ArrayBlockingQueue[A](capacity)
  private val dropped = new AtomicLong(0)
  private val failed = new AtomicLong(0)
  @volatile private var running = true

  /** True when accepted; false when dropped (queue full). */
  def offer(doc: A): Boolean = {
    val accepted = queue.offer(doc)
    if (!accepted) dropped.incrementAndGet()
    accepted
  }

  def queued: Int = queue.size()
  def droppedTotal: Long = dropped.get()
  def failedTotal: Long = failed.get()

  /** Waits up to `flushMillis` for the first document, then takes what is there, up to `batchSize`. */
  private[commons] def nextBatch(): Seq[A] = {
    val batch = new ArrayBuffer[A](batchSize)
    Option(queue.poll(flushMillis, TimeUnit.MILLISECONDS)).foreach { first =>
      batch += first
      val deadline = System.currentTimeMillis() + flushMillis
      while (batch.size < batchSize && System.currentTimeMillis() < deadline) {
        Option(queue.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS)) match {
          case Some(doc) => batch += doc
          case None      => return batch.toSeq
        }
      }
    }
    batch.toSeq
  }

  private[commons] def flush(): Unit = {
    val batch = nextBatch()
    if (batch.nonEmpty) {
      val notIndexed = Try(send(batch)).fold(
        e => { logging.error(s"$name: batch of ${batch.size} failed: ${e.getMessage}"); batch.size },
        n => n
      )
      if (notIndexed > 0) failed.addAndGet(notIndexed)
    }
  }

  private val thread = new Thread(
    () => {
      var lastReport = System.currentTimeMillis()
      var reportedDropped = 0L
      var reportedFailed = 0L
      while (running || !queue.isEmpty) {
        try flush()
        catch { case e: Throwable => logging.error(s"$name: unexpected error: ${e.getMessage}") }
        val now = System.currentTimeMillis()
        if (now - lastReport >= 60000) {
          val d = dropped.get() - reportedDropped
          val f = failed.get() - reportedFailed
          if (d > 0 || f > 0) logging.warn(s"$name: last minute: $d lines dropped (queue full, $capacity), $f not indexed by elasticsearch, ${queue.size()} queued")
          reportedDropped += d
          reportedFailed += f
          lastReport = now
        }
      }
    },
    name
  )
  thread.setDaemon(true)

  def start(): LogBatcher[A] = { thread.start(); this }

  /** Stops accepting and sends what is queued. */
  def close(): Unit = { running = false; thread.join(10000) }
}
