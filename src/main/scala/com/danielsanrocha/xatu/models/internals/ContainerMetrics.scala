package com.danielsanrocha.xatu.models.internals

/**
 * One sample of a running docker container. `containerName` is the identity (it survives
 * a move to another server in the yeshua cluster), `containerId` changes on recreation.
 */
case class ContainerMetrics(
    server: String,
    containerName: String,
    containerId: String,
    image: String,
    collectedAt: Long,
    startedAt: Option[Long],
    restartCount: Int,
    oomKilled: Boolean,
    health: Option[String],
    cpuPercent: Double,
    cpuLimitCores: Double,
    memUsageBytes: Long,
    memLimitBytes: Long,
    netRxBytesPerSec: Double,
    netTxBytesPerSec: Double,
    blockReadBytesPerSec: Double,
    blockWriteBytesPerSec: Double,
    pids: Int
)

/** Where a container is running now. */
case class ContainerLocation(
    containerName: String,
    server: String,
    containerId: String,
    image: String,
    startedAt: Option[Long],
    lastSeen: Long
)

/** A container of the inventory with its most recent sample (None when it has no recent one). */
case class ContainerOverview(
    containerName: String,
    server: String,
    containerId: String,
    image: String,
    startedAt: Option[Long],
    lastSeen: Long,
    latest: Option[ContainerMetrics]
)
