package com.danielsanrocha.xatu

import com.danielsanrocha.xatu.controllers.StatusController
import com.danielsanrocha.xatu.managers.{APIObserverManager, LogContainerObserverManager, LogServiceObserverManager, ServiceObserverManager}
import com.danielsanrocha.xatu.repositories.{
  APIRepository,
  APIRepositoryImpl,
  ContainerRepository,
  ContainerRepositoryImpl,
  LogRepository,
  LogRepositoryImpl,
  ServiceRepository,
  ServiceRepositoryImpl,
  UserRepository,
  UserRepositoryImpl
}
import com.danielsanrocha.xatu.services.{APIService, APIServiceImpl, ContainerService, ContainerServiceImpl, LogService, LogServiceImpl, ServiceService, ServiceServiceImpl}
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.core.DockerClientBuilder
import com.typesafe.scalalogging.Logger
import com.typesafe.config.{Config, ConfigFactory}
import slick.jdbc.MySQLProfile.api._
import scala.concurrent.ExecutionContext

class TheGreatManager(implicit val client: Database, implicit val ec: ExecutionContext) {
  private val logging: Logger = Logger(this.getClass)

  logging.info("Creating repositories...")
  implicit val userRepository: UserRepository = new UserRepositoryImpl()
  implicit val serviceRepository: ServiceRepository = new ServiceRepositoryImpl()
  implicit val apiRepository: APIRepository = new APIRepositoryImpl()
  implicit val containerRepository: ContainerRepository = new ContainerRepositoryImpl()
  private implicit val conf: Config = ConfigFactory.load()

  implicit val logRepository: LogRepository =
    if (conf.getString("elasticsearch.active") == "true") new LogRepositoryImpl("elasticsearch", ec)
    else new com.danielsanrocha.xatu.repositories.LogRepositoryDummyImpl()

  logging.info("Instantiating docker client...")
  implicit val dockerClient: DockerClient = DockerClientBuilder.getInstance.build

  logging.info("Creating services...")
  private implicit val serviceService: ServiceService = new ServiceServiceImpl()
  private implicit val apiService: APIService = new APIServiceImpl()
  private implicit val logService: LogService = new LogServiceImpl()
  private implicit val containerService: ContainerService = new ContainerServiceImpl()

  logging.info("Loading configuration file and accessing it...")

  logging.info("Instantiating Observers Managers...")
  private implicit val apiObserverManager: APIObserverManager = new APIObserverManager()
  private implicit val logServiceManager: LogServiceObserverManager = new LogServiceObserverManager()
  private implicit val serviceObserverManager: ServiceObserverManager = new ServiceObserverManager()
  private implicit val logContainerManager: LogContainerObserverManager = new LogContainerObserverManager()

  private val managersEnable = conf.getBoolean("managers.enabled")

  private val token = conf.getString("telegram.bot_token")

  def start(): Unit = {
    if (token != "inactive") {
      logging.info("Starting TelegramNotifier...")

      val chatId = conf.getString("telegram.chat_id")
      val telegramNotifier = new TelegramNotifier(token = token, chatId = chatId, containerService, apiService, serviceService, ec)

      telegramNotifier.start()
    } else {
      logging.info("Telegram token is to inactive.")
    }

    if (managersEnable) {
      logging.info("Starting managers...")
      apiObserverManager.start()
      logServiceManager.start()
      serviceObserverManager.start()
      logContainerManager.start()
    }
  }

  val statusController = new StatusController()
}
