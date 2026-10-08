package com.danielsanrocha.xatu.repositories

import com.danielsanrocha.xatu.commons.LogBatcher
import com.danielsanrocha.xatu.exceptions.BadArgumentException
import com.danielsanrocha.xatu.models.internals.{Log, LogContainer, LogService}
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.typesafe.scalalogging.Logger
import com.typesafe.config.{Config, ConfigFactory}
import scalaj.http.{Http, HttpOptions, HttpRequest}

import scala.concurrent.Future
import scala.io.Source
import scala.language.postfixOps

class LogRepositoryImpl(config: String, implicit val ec: scala.concurrent.ExecutionContext) extends LogRepository {
  private val logging: Logger = Logger(this.getClass)
  private val jsonMapper = JsonMapper.builder().addModule(DefaultScalaModule).build();

  logging.info("Loading configuration file and acessing it...")
  private implicit val conf: Config = ConfigFactory.load()

  private val esHost = conf.getString(s"$config.host")
  private val esPort = conf.getString(s"$config.port")
  private val esIndex = conf.getString(s"$config.index")
  private val defaultSearchSize = conf.getInt(s"$config.search_size")
  // Elasticsearch default index.max_result_window
  private val maxSearchSize = 10000
  private val server = optionalString("server")
  private val credentials = for {
    user <- optionalString(s"$config.user")
    password <- optionalString(s"$config.password")
  } yield (user, password)

  private def optionalString(path: String): Option[String] =
    if (conf.hasPath(path)) Some(conf.getString(path)).filter(_.nonEmpty) else None

  private def request(route: String): HttpRequest =
    credentials.fold(Http(route)) { case (user, password) => Http(route).auth(user, password) }

  private val indexMapping = Source.fromResource("logIndex.json").mkString

  /** Lines are queued and sent in _bulk by one thread; see LogBatcher. */
  private val batcher = new LogBatcher[(String, String)](
    capacity = conf.getInt(s"$config.queue_size"),
    batchSize = conf.getInt(s"$config.bulk_size"),
    flushMillis = conf.getLong(s"$config.bulk_interval_ms"),
    send = bulk,
    name = "elasticsearch-bulk"
  ).start()

  /** Sends a batch with the bulk api, returns how many documents were not indexed. */
  private def bulk(docs: Seq[(String, String)]): Int = {
    val body = docs.map { case (id, json) => s"""{"index":{"_index":"$esIndex","_id":"$id"}}\n$json\n""" }.mkString
    val result = request(s"$esHost:$esPort/_bulk")
      .option(HttpOptions.connTimeout(10000))
      .option(HttpOptions.readTimeout(30000))
      .header("content-type", "application/x-ndjson")
      .postData(body)
      .execute()
    if (result.code != 200) throw new Exception(s"Elasticsearch returned status ${result.code} on _bulk: ${result.body.take(200)}")
    val response = ujson.read(result.body)
    if (!response("errors").bool) 0
    else {
      val failed = response("items").arr.map(_("index")).filter(i => i("status").num >= 300)
      failed.headOption.foreach(i => logging.error(s"ES bulk error: ${i.obj.get("error").map(_.render()).getOrElse("?").take(300)}"))
      failed.size
    }
  }

  private def enqueue(documentId: String, json: String): Future[Unit] = {
    batcher.offer((documentId, json))
    Future.unit
  }

  def createIndex(): Future[Unit] = {
    Future {
      try {
        val route = s"$esHost:$esPort/$esIndex"
        logging.info(s"Creating index at route $route")
        val result = request(route)
          .option(HttpOptions.connTimeout(10000))
          .option(HttpOptions.readTimeout(10000))
          .header("content-type", "application/json")
          .put(indexMapping)
          .execute()

        if (result.code != 200) {
          throw new Exception(s"ES returned status ${result.code}! Message: ${result.body}")
        }

      } catch {
        case e: Exception =>
          logging.error(s"Error creating index ${esIndex} on elasticsearch. Error: ${e.getMessage}")
          throw e
      }
    }
  }

  override def create(documentId: String, log: LogService): Future[Unit] =
    enqueue(documentId, jsonMapper.writeValueAsString(log.copy(server = server)))

  override def create(documentId: String, log: LogContainer): Future[Unit] =
    enqueue(documentId, jsonMapper.writeValueAsString(log.copy(server = server)))

  override def search(query: String, size: Option[Int]): Future[Seq[Log]] = {
    Future {
      if (query.contains("\"")) {
        throw new BadArgumentException("Invalid query contains \"")
      }

      val searchSize = size.getOrElse(defaultSearchSize)
      if (searchSize < 1 || searchSize > maxSearchSize) {
        throw new BadArgumentException(s"Invalid size $searchSize, must be between 1 and $maxSearchSize")
      }

      val route = s"$esHost:$esPort/$esIndex/_search"
      logging.debug(s"Making a search for logs with query `$query` and size $searchSize")

      val data = s"{\"size\": $searchSize, \"sort\":[{\"created_at\":{\"order\": \"desc\"}}], \"query\": {\"query_string\" : {\"query\": \"$query\"}}}"

      logging.debug(s"Search post data: $data")

      val result = request(route)
        .header("Content-Type", "application/json")
        .postData(data)
        .option(HttpOptions.connTimeout(10000))
        .option(HttpOptions.readTimeout(10000))
        .execute()

      if (result.code != 200) {
        throw new Exception(s"Elasticsearch returned status ${result.code} while searching with query `${query}`")
      }

      logging.debug(s"ES search response: ${result.body}")

      val body = ujson.read(result.body)

      body("hits")("hits").arr map { hit =>
        val source = hit("_source")
        val logServer = source.obj.get("server").map(_.str)
        try {
          val service_id = source("service_id").toString
          LogService(
            service_id.toLong,
            source("service_name").str,
            source("filename").str,
            source("message").str,
            source("created_at").toString().toLong,
            logServer
          )
        } catch {
          case _: NoSuchElementException =>
            LogContainer(source("container_id").toString.toLong, source("container_name").str, source("message").str, source("created_at").toString().toLong, logServer)
          case e: Exception => throw e
        }
      } toSeq
    }
  }

  override def status(): Future[Unit] = {
    val route = s"$esHost:$esPort/$esIndex"
    logging.debug(s"Checking index on $route...")

    Future {
      val result = request(route)
        .option(HttpOptions.connTimeout(3000))
        .option(HttpOptions.readTimeout(3000))
        .execute()

      if (result.code != 200) throw new Exception(s"Elasticsearch response wrong status code (${result.code}) expected 200.")
    }
  }
}
