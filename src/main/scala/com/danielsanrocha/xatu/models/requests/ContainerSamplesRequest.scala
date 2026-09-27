package com.danielsanrocha.xatu.models.requests

import com.twitter.finatra.http.annotations.QueryParam

case class ContainerSamplesRequest(
    @QueryParam name: Option[String] = None,
    @QueryParam server: Option[String] = None,
    @QueryParam from: Option[Long] = None,
    @QueryParam to: Option[Long] = None
)
