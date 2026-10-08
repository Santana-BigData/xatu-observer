package com.danielsanrocha.xatu.models.requests

case class NewApiToken(
    name: String,
    readOnly: Boolean = true
)
