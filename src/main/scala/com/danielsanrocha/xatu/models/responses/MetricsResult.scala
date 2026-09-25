package com.danielsanrocha.xatu.models.responses

import com.danielsanrocha.xatu.models.internals.ServerMetrics

case class MetricsResult(
    server: String,
    from: Long,
    to: Long,
    step: Long,
    points: Seq[ServerMetrics]
)
