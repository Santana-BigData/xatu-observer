package com.danielsanrocha.xatu.models.internals

/** A process using a GPU, from `nvidia-smi --query-compute-apps`. */
case class GpuProcess(pid: Long, name: String, memUsedBytes: Long)

/** One sample of one GPU. Percentages are 0-100, memory in bytes, power in watts. */
case class GpuMetrics(
    server: String,
    collectedAt: Long,
    gpuIndex: Int,
    uuid: String,
    name: String,
    driverVersion: String,
    utilizationGpuPercent: Int,
    utilizationMemPercent: Int,
    memUsedBytes: Long,
    memTotalBytes: Long,
    temperatureC: Int,
    powerDrawW: Double,
    powerLimitW: Double,
    fanPercent: Option[Int],
    smClockMhz: Int,
    memClockMhz: Int,
    pstate: String,
    throttleReasons: Seq[String],
    processes: Seq[GpuProcess]
)

/** A GPU of the inventory. */
case class GpuInfo(
    server: String,
    gpuIndex: Int,
    uuid: String,
    name: String,
    driverVersion: String,
    memTotalBytes: Long,
    lastSeen: Long
)
