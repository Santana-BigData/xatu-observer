package com.danielsanrocha.xatu.models.responses

import com.danielsanrocha.xatu.commons.StatusRules
import com.danielsanrocha.xatu.models.internals.{API, CheckKind, ServerCheck, Service}

import java.sql.Timestamp

/** Service with the status aggregated from every server and the check of each one. */
case class ServiceResponse(
    id: Long,
    name: String,
    logFileDirectory: String,
    logFileRegex: String,
    pidFile: String,
    status: Char,
    createDate: Timestamp,
    updateDate: Timestamp,
    servers: Seq[ServerCheck]
)

object ServiceResponse {
  def apply(s: Service, checks: Map[Long, Seq[ServerCheck]]): ServiceResponse = {
    val servers = checks.getOrElse(s.id, Seq()).sortBy(_.server)
    ServiceResponse(s.id, s.name, s.logFileDirectory, s.logFileRegex, s.pidFile, StatusRules.aggregate(CheckKind.Service, servers), s.createDate, s.updateDate, servers)
  }
}

/** API with the status aggregated from every server and the check of each one. */
case class APIResponse(
    id: Long,
    name: String,
    host: String,
    port: Int,
    healthcheckRoute: String,
    status: Char,
    createDate: Timestamp,
    updateDate: Timestamp,
    servers: Seq[ServerCheck]
)

object APIResponse {
  def apply(a: API, checks: Map[Long, Seq[ServerCheck]]): APIResponse = {
    val servers = checks.getOrElse(a.id, Seq()).sortBy(_.server)
    APIResponse(a.id, a.name, a.host, a.port, a.healthcheckRoute, StatusRules.aggregate(CheckKind.API, servers), a.createDate, a.updateDate, servers)
  }
}
