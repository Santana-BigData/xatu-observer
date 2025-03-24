ThisBuild / version := "3.2.0"

ThisBuild / scalaVersion := "2.13.10"

lazy val root = (project in file("."))
  .settings(name := "xatu-observer")

assemblyMergeStrategy in assembly := {
  case x if x.contains("InjectionManagerFactory") => MergeStrategy.first
  case x if x.contains("ServiceLocatorGenerator") => MergeStrategy.first
  case PathList("META-INF", xs @ _*) =>
    xs map { _.toLowerCase } match {
      case "services" :: xs =>
        MergeStrategy.filterDistinctLines
      case _ => MergeStrategy.discard
    }
  case "reference.conf" => MergeStrategy.concat
  case _                => MergeStrategy.first
}

lazy val versions = new {
  val finatra = "21.2.0"
  val finagle = "21.2.0"
  val twitter = "21.2.0"
  val typesafeConfig = "1.3.1"
  val activation = "1.2.0"
  val scalaUUID = "0.3.1"
  val jedis = "4.3.0"
  val slick = "3.5.1"
  val mysql = "8.0.31"
  val scalaj = "2.4.2"
  val uJson = "2.0.0"
  val docker = "3.0.14"
  val javax = "2.1"
  val jersey = "2.37"
  val hk2 = "2.6.1"
  val guava = "2.26-b03"
  val logback = "1.5.6"
  val scalaLogging = "3.9.5"
  val netty = "4.0.27.Final"
}

lazy val versionsTest = new {
  val scalatest = "3.2.14"
  val mockito = "3.2.10.0"
  val twitter = "21.3.0"
  val h2 = "2.1.214"
}

libraryDependencies ++= Seq(
  "com.twitter" %% "finatra-http" % versions.finatra,
  "com.twitter" %% "finatra-thrift" % versions.finatra,
  "com.twitter" %% "finagle-http" % versions.finagle,
  "com.twitter" %% "finagle-stats" % versions.finagle,
  "com.typesafe.slick" %% "slick" % versions.slick,
  "com.typesafe.slick" %% "slick-hikaricp" % versions.slick,
  "mysql" % "mysql-connector-java" % versions.mysql,
  "com.typesafe" % "config" % versions.typesafeConfig,
  "com.sun.activation" % "javax.activation" % versions.activation,
  "io.jvm.uuid" %% "scala-uuid" % versions.scalaUUID,
  "redis.clients" % "jedis" % versions.jedis,
  "org.scalaj" %% "scalaj-http" % versions.scalaj,
  "com.lihaoyi" %% "upickle" % versions.uJson,
  "com.github.docker-java" % "docker-java" % versions.docker,
  "org.glassfish.jersey.core" % "jersey-common" % versions.jersey,
  "org.glassfish.jersey.core" % "jersey-client" % versions.jersey,
  "org.glassfish.jersey.inject" % "jersey-hk2" % versions.jersey,
  "org.glassfish.hk2" % "hk2-api" % versions.hk2,
  "org.glassfish.jersey.bundles.repackaged" % "jersey-guava" % versions.guava,
  "com.typesafe.scala-logging" %% "scala-logging" % versions.scalaLogging,
  "ch.qos.logback" % "logback-classic" % versions.logback,
  "io.netty" % "netty-transport-native-epoll" % versions.netty
)

Test / parallelExecution := false

libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" % versionsTest.scalatest % Test,
  "org.scalatestplus" %% "mockito-3-4" % versionsTest.mockito % Test,
  "com.twitter" %% "finatra-jackson" % versionsTest.twitter % Test classifier "tests",
  "com.twitter" %% "finatra-http-server" % versionsTest.twitter % Test classifier "tests",
  "com.twitter" %% "inject-server" % versionsTest.twitter % Test classifier "tests",
  "com.twitter" %% "inject-app" % versionsTest.twitter % Test classifier "tests",
  "com.twitter" %% "inject-core" % versionsTest.twitter % Test classifier "tests",
  "com.twitter" %% "inject-modules" % versionsTest.twitter % Test classifier "tests",
  "com.h2database" % "h2" % versionsTest.h2 % Test
)
