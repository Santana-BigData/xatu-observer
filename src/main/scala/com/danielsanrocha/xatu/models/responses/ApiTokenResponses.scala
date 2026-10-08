package com.danielsanrocha.xatu.models.responses

import com.danielsanrocha.xatu.models.internals.ApiToken

import java.sql.Timestamp

/** A token without its secret. */
case class ApiTokenResponse(
    id: Long,
    name: String,
    tokenPrefix: String,
    readOnly: Boolean,
    createdBy: Long,
    createDate: Timestamp,
    lastUsed: Option[Timestamp],
    revokedDate: Option[Timestamp],
    active: Boolean
)

object ApiTokenResponse {
  def apply(t: ApiToken): ApiTokenResponse =
    ApiTokenResponse(t.id, t.name, t.tokenPrefix, t.readOnly, t.createdBy, t.createDate, t.lastUsed, t.revokedDate, t.revokedDate.isEmpty)
}

/** Answer of the creation: the only time the token itself is shown. */
case class CreatedApiToken(
    id: Long,
    name: String,
    token: String,
    readOnly: Boolean,
    requestId: String
)
