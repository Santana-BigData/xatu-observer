package com.danielsanrocha.xatu.commons

/**
 * Status reported for an api from the result of its checks: it only fails after
 * `failuresToFail` consecutive failed checks, so one slow answer (network hiccup of this
 * server, elasticsearch holding a thread) does not flip the status. One success resets it.
 */
case class ApiCheckState(failuresToFail: Int, consecutiveFailures: Int = 0) {

  def success: ApiCheckState = copy(consecutiveFailures = 0)

  def failure: ApiCheckState = copy(consecutiveFailures = consecutiveFailures + 1)

  /** 'F' when the failures reached the threshold, 'W' otherwise (including while failing). */
  def status: Char = if (consecutiveFailures >= failuresToFail) 'F' else 'W'

  /** Message saved with the check: the error while failing, nothing while working. */
  def message(error: Option[String]): Option[String] =
    if (consecutiveFailures == 0) None
    else error.map(e => if (status == 'F') e else s"$consecutiveFailures/$failuresToFail failed checks: $e")
}
