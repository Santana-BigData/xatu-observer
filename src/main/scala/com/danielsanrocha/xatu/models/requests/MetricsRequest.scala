package com.danielsanrocha.xatu.models.requests

import com.twitter.finatra.http.annotations.QueryParam

case class MetricsRequest(
    @QueryParam server: String,
    @QueryParam from: Option[Long] = None,
    @QueryParam to: Option[Long] = None,
    @QueryParam step: Option[Long] = None
)
