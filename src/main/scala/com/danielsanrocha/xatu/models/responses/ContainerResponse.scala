package com.danielsanrocha.xatu.models.responses

import com.danielsanrocha.xatu.models.internals.{Container, ContainerInfo, Data}

import java.sql.Timestamp

case class ContainerResponse(
    override val id: Long,
    override val name: String,
    info: Option[ContainerInfo],
    status: Char,
    createDate: Timestamp,
    updateDate: Timestamp
) extends Data(id, name) {
  def container: Container = Container(id, name, createDate, updateDate)

  override def configuration: Any = (id, name, info)
}
