package com.danielsanrocha.xatu.models.internals

class Data(val id: Long, val name: String) {
  def apply(id: Long, name: String) = new Data(id, name)

  /**
   * What the observer depends on. Observers are reloaded only when it changes, not when
   * the status/update date written by the observers themselves change: the table is
   * shared by every Xatu (galera), so the status of a row changes all the time.
   */
  def configuration: Any = (id, name)
}
