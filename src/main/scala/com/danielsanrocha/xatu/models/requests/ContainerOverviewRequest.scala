package com.danielsanrocha.xatu.models.requests

import com.twitter.finatra.http.annotations.QueryParam

/** q: words that must all appear in name, image or id. server: comma separated. name: exact. */
case class ContainerOverviewRequest(
    @QueryParam q: Option[String] = None,
    @QueryParam server: Option[String] = None,
    @QueryParam name: Option[String] = None
)
