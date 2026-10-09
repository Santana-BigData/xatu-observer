package com.danielsanrocha.xatu.commons

import java.io.{File, InputStream}
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters._

/** Runs a bash script once, with a timeout, keeping the end of its output (stdout and stderr). */
object ScriptRunner {

  /** `exitCode` is None when the script timed out (it was killed). */
  case class Result(exitCode: Option[Int], output: String, durationMillis: Long) {
    def ok: Boolean = exitCode.contains(0)
  }

  private val KeptOutput = 4000

  def run(script: String, timeoutSeconds: Int, env: Map[String, String] = Map()): Result = {
    val started = System.currentTimeMillis()
    val builder = new ProcessBuilder("bash", "-c", script)
      .redirectErrorStream(true)
      .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
    builder.environment().putAll(env.asJava)
    val process = builder.start()

    // read while it runs, or a script that prints a lot blocks on a full pipe
    val output = new StringBuilder
    val reader = new Thread(() => readTail(process.getInputStream, output), "script-output")
    reader.setDaemon(true)
    reader.start()

    try {
      val finished = process.waitFor(timeoutSeconds.toLong, TimeUnit.SECONDS)
      if (!finished) kill(process)
      // a background child may keep the pipe open after bash exits: do not wait for it forever
      reader.join(2000)
      val text = output.synchronized(output.toString)
      Result(if (finished) Some(process.exitValue()) else None, text, System.currentTimeMillis() - started)
    } catch {
      case e: InterruptedException =>
        kill(process)
        throw e
    }
  }

  private def kill(process: Process): Unit = {
    process.descendants().iterator().asScala.foreach(_.destroyForcibly())
    process.destroyForcibly()
  }

  private def readTail(in: InputStream, output: StringBuilder): Unit = {
    val buffer = new Array[Byte](4096)
    try {
      var n = in.read(buffer)
      while (n >= 0) {
        output.synchronized {
          output.append(new String(buffer, 0, n, StandardCharsets.UTF_8))
          if (output.length > 2 * KeptOutput) output.delete(0, output.length - KeptOutput)
        }
        n = in.read(buffer)
      }
    } catch {
      case _: java.io.IOException => // the process was killed
    } finally in.close()
  }

  /** Short reason of a failure for the status and the telegram alert: exit code and last lines printed. */
  def failureMessage(result: Result, timeoutSeconds: Int, maxLength: Int = 300): String = {
    val lines = result.output.split("\n").map(_.trim).filter(_.nonEmpty)
    val last = lines.takeRight(3).mkString(" | ")
    val tail = if (last.length > maxLength) "..." + last.takeRight(maxLength) else last
    val reason = result.exitCode match {
      case Some(code) => s"exit $code"
      case None       => s"timed out after ${timeoutSeconds}s"
    }
    if (tail.isEmpty) reason else s"$reason: $tail"
  }
}
