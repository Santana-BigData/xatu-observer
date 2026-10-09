package com.danielsanrocha.xatu

import com.danielsanrocha.xatu.commons.{AlertMetricSeries, ContainerStatsReader, HardwareSensorsReader, NvidiaSmiReader, RedisPool, SystemMetricsReader}
import com.danielsanrocha.xatu.controllers.{AlertRuleController, ContainerMetricsController, GpuMetricsController, MetricsController, SensorsController, StatusController}
import com.danielsanrocha.xatu.managers.{APIObserverManager, LogContainerObserverManager, LogServiceObserverManager, ScriptObserverManager, ServiceObserverManager}
import com.danielsanrocha.xatu.repositories.{
  APIRepository,
  APIRepositoryImpl,
  ContainerRepository,
  ContainerRepositoryImpl,
  LogRepository,
  LogRepositoryImpl,
  ContainerMetricsRepository,
  ContainerMetricsRepositoryDummyImpl,
  ContainerMetricsRepositoryImpl,
  GpuMetricsRepository,
  GpuMetricsRepositoryDummyImpl,
  GpuMetricsRepositoryImpl,
  MetricsRepository,
  MetricsRepositoryDummyImpl,
  MetricsRepositoryImpl,
  CassandraSession,
  AlertRuleRepositoryImpl,
  SensorsRepository,
  SensorsRepositoryDummyImpl,
  SensorsRepositoryImpl,
  ScriptRepository,
  ScriptRepositoryImpl,
  ServiceRepository,
  ServiceRepositoryImpl,
  StatusRepository,
  StatusRepositoryImpl,
  UserRepository,
  UserRepositoryImpl
}
import com.danielsanrocha.xatu.services.{
  APIService,
  APIServiceImpl,
  ContainerService,
  ContainerServiceImpl,
  LogService,
  LogServiceImpl,
  ScriptService,
  ScriptServiceImpl,
  AlertRuleService,
  ServiceService,
  ServiceServiceImpl
}
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.core.DockerClientBuilder
import com.typesafe.scalalogging.Logger
import com.typesafe.config.{Config, ConfigFactory}
import redis.clients.jedis.Jedis
import redis.clients.jedis.util.Pool
import slick.jdbc.MySQLProfile.api._
import scala.concurrent.ExecutionContext

class TheGreatManager(implicit val client: Database, implicit val ec: ExecutionContext) {
  private val logging: Logger = Logger(this.getClass)

  logging.info("Creating repositories...")
  implicit val userRepository: UserRepository = new UserRepositoryImpl()
  implicit val serviceRepository: ServiceRepository = new ServiceRepositoryImpl()
  implicit val apiRepository: APIRepository = new APIRepositoryImpl()
  implicit val containerRepository: ContainerRepository = new ContainerRepositoryImpl()
  implicit val scriptRepository: ScriptRepository = new ScriptRepositoryImpl()
  private implicit val conf: Config = ConfigFactory.load()

  implicit val logRepository: LogRepository =
    if (conf.getString("elasticsearch.active") == "true") new LogRepositoryImpl("elasticsearch", ec)
    else new com.danielsanrocha.xatu.repositories.LogRepositoryDummyImpl()

  /** Name of this Xatu in the checks, alerts and metrics: SERVER env var or hostname. */
  val server: String =
    (if (conf.hasPath("server")) Some(conf.getString("server")).filter(_.nonEmpty) else None)
      .getOrElse(java.net.InetAddress.getLocalHost.getHostName)

  logging.info("Connecting to redis...")
  implicit val cache: Pool[Jedis] = RedisPool.create(conf)

  private val clusterPeriod = conf.getLong("cluster.period_seconds")
  implicit val statusRepository: StatusRepository = new StatusRepositoryImpl(cache, conf.getLong("cluster.status_ttl_seconds"))
  val leaderElection = new LeaderElection(server, statusRepository, clusterPeriod)

  logging.info("Creating metrics repository...")
  private val cassandraActive = conf.getString("cassandra.active") == "true"
  private lazy val cassandraSession = CassandraSession.fromConfig(conf)
  implicit val metricsRepository: MetricsRepository =
    if (cassandraActive) new MetricsRepositoryImpl(cassandraSession) else new MetricsRepositoryDummyImpl()
  implicit val containerMetricsRepository: ContainerMetricsRepository =
    if (cassandraActive) new ContainerMetricsRepositoryImpl(cassandraSession) else new ContainerMetricsRepositoryDummyImpl()
  implicit val gpuMetricsRepository: GpuMetricsRepository =
    if (cassandraActive) new GpuMetricsRepositoryImpl(cassandraSession) else new GpuMetricsRepositoryDummyImpl()
  implicit val sensorsRepository: SensorsRepository =
    if (cassandraActive) new SensorsRepositoryImpl(cassandraSession) else new SensorsRepositoryDummyImpl()

  logging.info("Instantiating docker client...")
  implicit val dockerClient: DockerClient = DockerClientBuilder.getInstance.build

  logging.info("Creating services...")
  private implicit val serviceService: ServiceService = new ServiceServiceImpl()
  private implicit val apiService: APIService = new APIServiceImpl()
  private implicit val logService: LogService = new LogServiceImpl()
  private implicit val containerService: ContainerService = new ContainerServiceImpl()
  private implicit val scriptService: ScriptService = new ScriptServiceImpl()

  logging.info("Loading configuration file and accessing it...")

  logging.info("Instantiating Observers Managers...")
  private implicit val apiObserverManager: APIObserverManager = new APIObserverManager(server, statusRepository, conf.getInt("api.check_failures_to_fail"))
  private implicit val logServiceManager: LogServiceObserverManager = new LogServiceObserverManager()
  private implicit val serviceObserverManager: ServiceObserverManager = new ServiceObserverManager(server, statusRepository)
  private implicit val logContainerManager: LogContainerObserverManager = new LogContainerObserverManager()
  private val scriptObserverManager: ScriptObserverManager = new ScriptObserverManager(server, statusRepository)

  private val managersEnable = conf.getBoolean("managers.enabled")

  private val token = conf.getString("telegram.bot_token")
  val metricsInterval: Int = conf.getInt("metrics.interval_seconds")

  val alertRuleService = new AlertRuleService(
    new AlertRuleRepositoryImpl(),
    new AlertMetricSeries(metricsRepository, gpuMetricsRepository, sensorsRepository),
    metricsInterval
  )

  def start(): Unit = {
    logging.info(s"Starting as server $server...")
    new ServerHeartbeat(server, statusRepository, clusterPeriod).start()
    leaderElection.start()

    if (token != "inactive") {
      logging.info("Starting TelegramNotifier (only the leader sends)...")
      new TelegramNotifier(
        token,
        conf.getString("telegram.chat_id"),
        leaderElection,
        statusRepository,
        serviceRepository,
        apiRepository,
        containerRepository,
        scriptRepository,
        metricsRepository,
        alertRuleService,
        conf.getLong("telegram.repeat_minutes"),
        conf.getLong("telegram.agent_stale_minutes"),
        conf.getInt("telegram.confirm_rounds")
      ).start()
    } else {
      logging.info("Telegram token is to inactive.")
    }

    if (metricsRepository.active) {
      val reader = new SystemMetricsReader(
        server = server,
        netInterfaces = conf.getString("metrics.net_interfaces").split(",").map(_.trim).filter(_.nonEmpty).toSeq,
        diskPath = conf.getString("metrics.disk_path")
      )
      new MetricsCollector(reader, metricsRepository, metricsInterval).start()

      val nvidia = new NvidiaSmiReader(server, conf.getString("metrics.nvidia_smi"))
      if (nvidia.available) new GpuMetricsCollector(nvidia, gpuMetricsRepository, metricsInterval).start()
      else logging.info("No NVIDIA GPU found (nvidia-smi), gpu metrics are not collected.")

      val sensors = new HardwareSensorsReader(server, conf.getString("metrics.ipmi_command"))
      if (!sensors.ipmiAvailable) logging.info("ipmi is not available, fans and ambient temperature are not collected (cpu temperatures are).")
      new SensorsCollector(sensors, sensorsRepository, metricsInterval).start()

      ContainerStatsReader.dockerUrl(sys.env.get("DOCKER_HOST")) match {
        case Some(url) =>
          new ContainerMetricsCollector(new ContainerStatsReader(server, url), containerMetricsRepository, metricsInterval).start()
        case None =>
          logging.warn("DOCKER_HOST is not a tcp address (e.g. tcp://127.0.0.1:2375), docker container metrics are not collected.")
      }
    } else {
      logging.info("Cassandra is inactive, server metrics are not collected.")
    }

    if (managersEnable) {
      logging.info("Starting managers...")
      apiObserverManager.start()
      logServiceManager.start()
      serviceObserverManager.start()
      logContainerManager.start()
      scriptObserverManager.start()
      new ContainerStatusCollector(server, containerRepository, dockerClient, statusRepository, clusterPeriod).start()
    }
  }

  val statusController = new StatusController(server)
  val metricsController = new MetricsController(metricsInterval)
  val containerMetricsController = new ContainerMetricsController(metricsInterval)
  val gpuMetricsController = new GpuMetricsController(metricsInterval)
  val sensorsController = new SensorsController(metricsInterval)
  val alertRuleController = new AlertRuleController(alertRuleService, metricsInterval)
}
