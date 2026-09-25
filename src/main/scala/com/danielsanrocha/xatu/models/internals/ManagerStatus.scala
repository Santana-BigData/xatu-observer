package com.danielsanrocha.xatu.models.internals

import com.fasterxml.jackson.annotation.JsonProperty

case class ManagerStatus(
    @JsonProperty("api_observer_manager") val apiObserverManager: Seq[Status],
    @JsonProperty("log_service_observer_manager") val logServiceObserverManager: Seq[Status],
    @JsonProperty("service_observer_manager") val serviceObserverManager: Seq[Status],
    @JsonProperty("log_container_manager") val logContainerManager: Seq[Status],
    @JsonProperty("threads") numberOfThreads: Int,
    @JsonProperty("memory_usage") memoryUsage: Int,
    @JsonProperty("server") server: String,
    @JsonProperty("leader") leader: Option[String],
    @JsonProperty("servers") servers: Seq[XatuServerInfo]
)
