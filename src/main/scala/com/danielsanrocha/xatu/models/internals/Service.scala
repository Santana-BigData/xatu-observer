package com.danielsanrocha.xatu.models.internals

import java.sql.Timestamp

case class Service(
    override val id: Long,
    override val name: String,
    logFileDirectory: String,
    logFileRegex: String,
    pidFile: String,
    status: Char,
    createDate: Timestamp,
    updateDate: Timestamp
) extends Data(id, name) {
  override def configuration: Any = (id, name, logFileDirectory, logFileRegex, pidFile)
}
