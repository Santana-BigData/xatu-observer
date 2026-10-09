package com.danielsanrocha.xatu.models.requests

import com.twitter.finatra.http.annotations.RouteParam

case class AlertRuleRequest(
    @RouteParam id: Long,
    name: String,
    metric: String,
    server: String = "",
    operator: String,
    threshold: Double,
    durationMinutes: Int,
    enabled: Boolean = true
)
