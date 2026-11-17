package com.danielsanrocha.xatu.commons

import java.nio.file.{Files, Paths}

/** Whether this server has docker, so machines without it (e.g. a small vps) skip the container checks. */
object DockerAvailability {

  /** Docker is there when DOCKER_HOST is set or the default unix socket exists. */
  def configured(dockerHost: Option[String], socketExists: => Boolean): Boolean =
    dockerHost.exists(_.trim.nonEmpty) || socketExists

  lazy val onThisServer: Boolean = configured(sys.env.get("DOCKER_HOST"), Files.exists(Paths.get("/var/run/docker.sock")))
}
