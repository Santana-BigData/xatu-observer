package com.danielsanrocha.xatu.commons

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import com.typesafe.scalalogging.Logger

import java.nio.file.{FileSystemNotFoundException, FileSystems, Files, Path, Paths}
import java.time.Instant
import scala.io.Source
import scala.jdk.CollectionConverters._
import scala.util.Using

/**
 * Versioned cassandra schema. Each file src/main/resources/cassandra/V<number>__<name>.cql
 * is applied once, in version order, and recorded in <keyspace>.schema_version.
 * To change the schema, add a new file with the next version (never edit an applied one).
 */
object CassandraMigrator {
  private val logging: Logger = Logger(this.getClass)

  private val resourceDir = "cassandra"
  private val FileName = """V(\d+)__(\w+)\.cql""".r

  case class Migration(version: Int, name: String, file: String)

  def parseMigrations(files: Seq[String]): Seq[Migration] =
    files
      .collect { case f @ FileName(version, name) => Migration(version.toInt, name, f) }
      .sortBy(_.version)

  /** Replaces ${var} placeholders. Fails on unknown ones so a typo never reaches the cluster. */
  def render(cql: String, vars: Map[String, String]): String = {
    val rendered = vars.foldLeft(cql) { case (acc, (k, v)) => acc.replace("${" + k + "}", v) }
    """\$\{(\w+)\}""".r.findFirstMatchIn(rendered).foreach { m =>
      throw new IllegalArgumentException(s"Unknown placeholder $${${m.group(1)}} in migration")
    }
    rendered
  }

  /** Splits a file in statements, ignoring `--` comments and blank lines. */
  def statements(cql: String): Seq[String] =
    cql.linesIterator
      .map(line => line.indexOf("--") match { case -1 => line; case i => line.substring(0, i) })
      .mkString("\n")
      .split(";")
      .map(_.trim)
      .filter(_.nonEmpty)
      .toSeq

  private def listResources(): Seq[String] = {
    val uri = getClass.getClassLoader.getResource(resourceDir).toURI
    val dir: Path =
      if (uri.getScheme == "jar") {
        val fs =
          try FileSystems.getFileSystem(uri)
          catch { case _: FileSystemNotFoundException => FileSystems.newFileSystem(uri, Map.empty[String, Any].asJava) }
        fs.getPath(resourceDir)
      } else Paths.get(uri)
    Using.resource(Files.list(dir))(_.iterator().asScala.map(_.getFileName.toString).toList)
  }

  private def read(file: String): String =
    Using.resource(Source.fromResource(s"$resourceDir/$file"))(_.mkString)

  private def appliedVersions(session: CqlSession, keyspace: String): Set[Int] = {
    val tableExists = session
      .execute(
        SimpleStatement.newInstance(
          "SELECT table_name FROM system_schema.tables WHERE keyspace_name = ? AND table_name = 'schema_version'",
          keyspace
        )
      )
      .one() != null

    if (!tableExists) Set.empty
    else session.execute(s"SELECT version FROM $keyspace.schema_version").all().asScala.map(_.getInt("version")).toSet
  }

  /** Applies the pending migrations. Returns the ones applied now. */
  def migrate(session: CqlSession, keyspace: String, replicationFactor: Int): Seq[Migration] = {
    val vars = Map("keyspace" -> keyspace, "replication_factor" -> replicationFactor.toString)
    val applied = appliedVersions(session, keyspace)
    val pending = parseMigrations(listResources()).filterNot(m => applied.contains(m.version))

    logging.info(s"Keyspace $keyspace: applied versions ${applied.toSeq.sorted.mkString(",")}, pending ${pending.map(_.file).mkString(",")}")

    pending.foreach { m =>
      logging.info(s"Applying ${m.file}...")
      statements(render(read(m.file), vars)).foreach { st =>
        logging.debug(s"Executing: $st")
        session.execute(SimpleStatement.newInstance(st))
      }
      session.execute(
        SimpleStatement.newInstance(
          s"INSERT INTO $keyspace.schema_version (version, name, applied_at) VALUES (?, ?, ?)",
          Int.box(m.version),
          m.name,
          Instant.now()
        )
      )
      logging.info(s"Applied ${m.file}")
    }
    pending
  }
}
