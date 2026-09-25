package com.danielsanrocha.xatu

import com.typesafe.scalalogging.Logger
import slick.jdbc.MySQLProfile.api._

import scala.io.Source
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor}
import scala.concurrent.duration._
import scala.language.postfixOps
import com.danielsanrocha.xatu.commons.{CassandraMigrator, Security}
import com.danielsanrocha.xatu.repositories.{CassandraSession, LogRepository, LogRepositoryDummyImpl, LogRepositoryImpl}
import com.typesafe.config.{Config, ConfigFactory}

import java.util.Scanner
import java.util.concurrent.Executors

object Main extends App {
  private val usage = """
  Usage

  start: Start the server.
  createTables: Create tables on the database.
  createIndex: Create ES index for logs.
  cassandraMigrate: Create/update the cassandra schema (src/main/resources/cassandra).
  createUser: Create an user, you will be prompt for the info.

  """
  private val logging = Logger(this.getClass)
  logging.info("Starting the application...")

  logging.error("Testing logging.error")
  logging.warn("Testing logging.warn")
  logging.info("Testing logging.info")
  logging.debug("Testing logging.debug")
  logging.trace("Testing logging.trace")

  private implicit val conf: Config = ConfigFactory.load()
  private implicit val ec: ExecutionContextExecutor = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(conf.getInt("num_threads")))

  if (args.length == 0) {
    println(usage)
  } else if (args(0) == "cassandraMigrate") {
    // does not need mysql, so it runs before the database pool is created
    val keyspace = conf.getString("cassandra.keyspace")
    val cassandra = CassandraSession.fromConfig(conf, withKeyspace = false)
    val exitCode = cassandra.session match {
      case None =>
        logging.error("Could not connect to cassandra, check CASSANDRA_CONTACT_POINTS and CASSANDRA_DATACENTER.")
        1
      case Some(session) =>
        try {
          val applied = CassandraMigrator.migrate(session, keyspace, conf.getInt("cassandra.replication_factor"))
          logging.info(if (applied.isEmpty) s"Keyspace $keyspace is up to date.\n" else s"Applied ${applied.map(_.file).mkString(", ")}.\n")
          0
        } catch {
          case e: Throwable =>
            logging.error(s"Error applying cassandra migrations: ${e.getMessage}")
            1
        }
    }
    cassandra.close()
    sys.exit(exitCode)
  } else {
    logging.info("Loading slick MySQLClient...")
    implicit val client: Database = Database.forConfig("mysql")

    args(0) match {
      case "start" =>
        logging.info("Creating logs repository...")
        implicit val logRepository: LogRepository =
          if (conf.getString("elasticsearch.active") == "true") {
            new LogRepositoryImpl("elasticsearch", ec)
          } else {
            new LogRepositoryDummyImpl()
          }

        logging.info(s"Instantiating the great manager...")
        implicit val greatManager: TheGreatManager = new TheGreatManager()
        greatManager.start()
        logging.info(s"Starting API...")
        val server = new XatuServer()
        server.main(args)

      case "createTables" =>
        logging.info("Creating tb_users table...")
        val userQuery = Source.fromResource("queries/CreateUsersTable.sql").mkString
        Await.result(client.run(sqlu"#$userQuery"), atMost = 10 second)

        logging.info("Creating tb_services table...")
        val serviceQuery = Source.fromResource("queries/CreateServicesTable.sql").mkString
        Await.result(client.run(sqlu"#$serviceQuery"), atMost = 10 second)

        logging.info("Creating tb_apis table...")
        val APIQuery = Source.fromResource("queries/CreateAPIsTable.sql").mkString
        Await.result(client.run(sqlu"#$APIQuery"), atMost = 10 second)

        logging.info("Creating tb_containers table...")
        val containerQuery = Source.fromResource("queries/CreateContainersTable.sql").mkString
        Await.result(client.run(sqlu"#$containerQuery"), atMost = 10 second)
        logging.info("Created!\n")

      case "createIndex" =>
        logging.info("Creating logs repository...")
        implicit val logRepository: LogRepository = new LogRepositoryImpl("elasticsearch", ec)

        logging.info("Creating index...")
        Await.result(logRepository.createIndex(), atMost = 10 second)
        logging.info("Created!\n")
        sys.exit(0)

      case "createUser" =>
        val stdin = new Scanner(System.in);
        println("Enter username:")
        val name = stdin.nextLine()
        println("Enter email:")
        val email = stdin.nextLine()
        println("Enter password:")
        val password = stdin.nextLine().mkString
        println("Confirm password:")
        val confirmPassword = stdin.nextLine().mkString

        if (password != confirmPassword) {
          throw new Exception("Password did not match!")
        }

        logging.info("Creating user...")
        Await.result(client.run(sqlu"INSERT INTO tb_users (name,email,password) VALUES ($name,$email,${Security.hash(password)});"), atMost = 10 second)
        logging.info("Created!\n")

      case _ => println(usage)
    }
  }
}
