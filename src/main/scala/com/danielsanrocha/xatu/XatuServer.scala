package com.danielsanrocha.xatu

import com.danielsanrocha.xatu.commons.RedisPool
import com.danielsanrocha.xatu.controllers._
import com.danielsanrocha.xatu.filters.{AuthorizeFilter, CORSFilter, ExceptionHandlerFilter, RequestIdFilter, TimeoutFilter}
import com.danielsanrocha.xatu.models.internals.TTL
import com.danielsanrocha.xatu.repositories._
import com.danielsanrocha.xatu.services._
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.core.DockerClientBuilder
import com.twitter.finatra.http.HttpServer
import com.twitter.finatra.http.routing.HttpRouter
import com.typesafe.scalalogging.Logger
import com.typesafe.config.{Config, ConfigFactory}
import redis.clients.jedis.Jedis
import redis.clients.jedis.util.Pool
import slick.jdbc.MySQLProfile.api.Database

import java.util.concurrent.TimeUnit
import com.twitter.util._

import scala.concurrent.duration.FiniteDuration

class XatuServer(implicit val client: Database, implicit val ec: scala.concurrent.ExecutionContext, implicit val logRepository: LogRepository, implicit val greatManager: TheGreatManager)
    extends HttpServer {
  private val logging: Logger = Logger(this.getClass)

  logging.info("Loading configuration file and accessing it...")
  private implicit val conf: Config = ConfigFactory.load()

  override def defaultHttpServerName: String = "XatuObserver"

  private val host = conf.getString("api.host")
  private val port = conf.getInt("api.port")
  override def defaultHttpPort: String = s"$host:$port"

  override def disableAdminHttpServer = true

  logging.info("Connecting to redis...")
  private implicit val redisTTL: TTL = TTL(conf.getInt("redis.ttl"))
  implicit val cache: Pool[Jedis] = RedisPool.create(conf)

  logging.info("Instantiating docker client...")
  implicit val dockerClient: DockerClient = DockerClientBuilder.getInstance.build

  logging.info("Creating repositories...")
  implicit val userRepository: UserRepository = new UserRepositoryImpl()
  implicit val serviceRepository: ServiceRepository = new ServiceRepositoryImpl()
  implicit val apiRepository: APIRepository = new APIRepositoryImpl()
  implicit val containerRepository: ContainerRepository = new ContainerRepositoryImpl()

  logging.info("Creating services...")
  private implicit val userService: UserService = new UserServiceImpl()
  private implicit val serviceService: ServiceService = new ServiceServiceImpl()
  private implicit val apiService: APIService = new APIServiceImpl()
  private implicit val logService: LogService = new LogServiceImpl()
  private implicit val containerService: ContainerService = new ContainerServiceImpl()

  logging.info("Getting api configuration...")
  private val authorizationHeader = conf.getString("api.auth.header")

  logging.info("Instantiating controllers...")
  private val indexController = new IndexController()
  implicit val healthcheckTimeout: FiniteDuration = new FiniteDuration(conf.getInt("api.healthcheck_timeout"), TimeUnit.MILLISECONDS)
  private val healthcheckController = new HealthcheckController()
  private val notFoundController = new NotFoundController()
  private val loginController = new LoginController()
  private val userController = new UserController()
  private val serviceController = new ServiceController()
  private val apiController = new APIController()
  private val logController = new LogController()
  private val containerController = new ContainerController()
  private val corsController = new CORSController()
  private val webController = new WebController()

  logging.info("Instantiating filters...")
  private val exceptionHandlerFilter = new ExceptionHandlerFilter()
  private val requestIdFilter = new RequestIdFilter()
  private val authorizeFilter = new AuthorizeFilter(authorizationHeader, cache)
  private val corsFilter = new CORSFilter()

  private val timeout = conf.getLong("api.timeout")
  private val timeoutFilter = new TimeoutFilter(Duration(timeout, TimeUnit.MILLISECONDS), ec)

  override protected def configureHttp(router: HttpRouter): Unit = {
    router
      .filter(corsFilter)
      .filter(requestIdFilter)
      .filter(exceptionHandlerFilter)
      .filter(timeoutFilter)
      .add(indexController)
      .add(loginController)
      .add(authorizeFilter, userController)
      .add(authorizeFilter, serviceController)
      .add(authorizeFilter, apiController)
      .add(authorizeFilter, logController)
      .add(authorizeFilter, containerController)
      .add(authorizeFilter, greatManager.statusController)
      .add(healthcheckController)
      .add(webController)
      .add(notFoundController)
      .add(corsController)
  }
}
