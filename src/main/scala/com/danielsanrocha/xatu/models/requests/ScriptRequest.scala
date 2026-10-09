package com.danielsanrocha.xatu.models.requests

import com.twitter.finatra.http.annotations.RouteParam

case class ScriptRequest(
    @RouteParam id: Long,
    name: String,
    script: String,
    intervalSeconds: Int,
    timeoutSeconds: Int
)
