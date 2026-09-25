package com.danielsanrocha.xatu.models.internals

import com.fasterxml.jackson.annotation.{JsonInclude, JsonProperty}

case class LogContainer(
    @JsonProperty("container_id") containerId: Long,
    @JsonProperty("container_name") containerName: String,
    message: String,
    @JsonProperty("created_at") createdAt: Long,
    @JsonInclude(JsonInclude.Include.NON_ABSENT) server: Option[String] = None
) extends Log
