package com.danielsanrocha.xatu.models.internals

import com.fasterxml.jackson.annotation.{JsonInclude, JsonProperty}

case class LogService(
    @JsonProperty("service_id") serviceId: Long,
    @JsonProperty("service_name") serviceName: String,
    filename: String,
    message: String,
    @JsonProperty("created_at") createdAt: Long,
    @JsonInclude(JsonInclude.Include.NON_ABSENT) server: Option[String] = None
) extends Log
