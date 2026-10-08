package com.danielsanrocha.xatu.models.internals

import com.twitter.finagle.context.Contexts

import java.sql.Timestamp

/** Permanent token for api integrations. Only the sha-256 of the token is stored. */
case class ApiToken(
    id: Long,
    name: String,
    tokenPrefix: String,
    tokenHash: String,
    readOnly: Boolean,
    createdBy: Long,
    createDate: Timestamp,
    lastUsed: Option[Timestamp],
    revokedDate: Option[Timestamp]
)

/** Set by AuthorizeFilter when the request was authenticated with an api token (not a login session). */
final case class ApiTokenAuth(id: Long, name: String, readOnly: Boolean)

object ApiTokenAuth extends Contexts.local.Key[ApiTokenAuth]
