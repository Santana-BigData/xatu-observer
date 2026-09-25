package com.danielsanrocha.xatu

import com.danielsanrocha.xatu.models.internals.{CheckKind, ServerCheck}
import com.danielsanrocha.xatu.repositories.{ContainerRepository, StatusRepository}
import com.github.dockerjava.api.DockerClient
import com.typesafe.scalalogging.Logger

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.jdk.CollectionConverters._

/**
 * Checks the registered containers that exist on this server. A container that does not
 * exist here runs on another server, so it is not reported (it is not a failure).
 */
class ContainerStatusCollector(server: String, containers: ContainerRepository, dockerClient: DockerClient, repository: StatusRepository, periodSeconds: Long)(
    implicit val ec: ExecutionContext
) extends Periodic("ContainerStatusCollector", periodSeconds) {
  private val logging: Logger = Logger(this.getClass)

  override protected def run(): Unit = {
    val registered = Await.result(containers.getAll(1000, 0), 30.seconds)
    // showAll: stopped containers exist here and must be reported as failing
    val local = dockerClient.listContainersCmd().withShowAll(true).exec().asScala.toSeq
    val byName = local.flatMap(c => c.getNames.toSeq.map(_.stripPrefix("/") -> c)).toMap
    val now = System.currentTimeMillis()

    registered.foreach { container =>
      byName.get(container.name) match {
        case Some(c) =>
          // docker status: "Up 3 hours", "Up 2 minutes (Paused)", "Exited (1) 5 minutes ago"...
          val running = c.getStatus.startsWith("Up") && !c.getStatus.contains("Paused")
          logging.debug(s"Container ${container.name} is ${c.getStatus} on $server")
          repository.save(
            ServerCheck(CheckKind.Container, container.id, server, if (running) 'W' else 'F', if (running) None else Option(c.getStatus), now)
          )
        case None =>
          logging.debug(s"Container ${container.name} does not exist on $server, not reporting it")
      }
    }
  }
}
