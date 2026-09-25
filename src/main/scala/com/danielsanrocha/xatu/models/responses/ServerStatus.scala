package com.danielsanrocha.xatu.models.responses

case class ServerStatus(
    redis: String,
    mysql: String,
    docker: String,
    elasticsearch: String,
    cassandra: String
)
