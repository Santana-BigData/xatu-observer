package com.danielsanrocha.xatu.models.internals

import com.fasterxml.jackson.annotation.JsonProperty

/** One sample of the host resources. Bytes are absolute values, network is a rate. */
case class ServerMetrics(
    server: String,
    collectedAt: Long,
    cpuPercent: Double,
    cpuCores: Int,
    @JsonProperty("load_1m") load1m: Double,
    @JsonProperty("load_5m") load5m: Double,
    @JsonProperty("load_15m") load15m: Double,
    memTotalBytes: Long,
    memUsedBytes: Long,
    memAvailableBytes: Long,
    swapTotalBytes: Long,
    swapUsedBytes: Long,
    diskTotalBytes: Long,
    diskUsedBytes: Long,
    netRxBytesPerSec: Double,
    netTxBytesPerSec: Double
)

case class MetricsServer(
    server: String,
    lastSeen: Long,
    cpuCores: Int,
    memTotalBytes: Long
)
