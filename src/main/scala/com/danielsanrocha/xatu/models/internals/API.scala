package com.danielsanrocha.xatu.models.internals

import java.sql.Timestamp

case class API(
    override val id: Long,
    override val name: String,
    host: String,
    port: Int,
    healthcheckRoute: String,
    status: Char,
    createDate: Timestamp,
    updateDate: Timestamp
) extends Data(id, name) {
  override def configuration: Any = (id, name, host, port, healthcheckRoute)
}
