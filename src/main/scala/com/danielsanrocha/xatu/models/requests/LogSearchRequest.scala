package com.danielsanrocha.xatu.models.requests

import com.twitter.finatra.http.annotations.QueryParam

case class LogSearchRequest(
    @QueryParam query: String,
    @QueryParam size: Option[Int] = None
)
