package com.danielsanrocha.xatu.commons

import com.danielsanrocha.xatu.models.internals.{CheckKind, SentAlert, ServerCheck, XatuServerInfo}

/** How the checks of every server become one status and the alerts sent to telegram. */
object StatusRules {

  /**
   * Status of a target from the checks of all servers. 'U' when no server checked it.
   * Services and containers run on specific servers: any failing server fails it.
   * APIs are the same url checked from every server: it fails only when most servers
   * fail, a single failing server is its own network problem.
   */
  def aggregate(kind: String, checks: Seq[ServerCheck]): Char =
    if (checks.isEmpty) 'U'
    else {
      val failing = checks.count(_.status == 'F')
      kind match {
        case CheckKind.API => if (failing * 2 > checks.size) 'F' else 'W'
        case _             => if (failing > 0) 'F' else 'W'
      }
    }

  /** Something wrong right now. `key` identifies it, so it is alerted once and resolved later. */
  case class Problem(key: String, message: String)

  case class Target(kind: String, id: Long, name: String)

  private def label(kind: String) = kind match {
    case CheckKind.API       => "API"
    case CheckKind.Container => "Container"
    case _                   => "Service"
  }

  private def detail(c: ServerCheck) = c.message.filter(_.nonEmpty).map(m => s" ($m)").getOrElse("")

  def problems(targets: Seq[Target], checks: Map[String, Map[Long, Seq[ServerCheck]]], servers: Seq[XatuServerInfo]): Seq[Problem] = {
    val targetProblems = targets.flatMap { t =>
      val targetChecks = checks.getOrElse(t.kind, Map.empty).getOrElse(t.id, Seq()).sortBy(_.server)
      val failing = targetChecks.filter(_.status == 'F')

      t.kind match {
        case CheckKind.API =>
          if (aggregate(t.kind, targetChecks) == 'F')
            Seq(
              Problem(
                s"${t.kind}::${t.id}",
                s"API ${t.name} is broken, failing on ${failing.size}/${targetChecks.size} servers: " +
                  failing.map(c => c.server + detail(c)).mkString(", ")
              )
            )
          else Seq()
        case kind =>
          failing.map(c => Problem(s"$kind::${t.id}::${c.server}", s"${label(kind)} ${t.name} is not running on ${c.server}${detail(c)}"))
      }
    }

    val downServers = servers.filterNot(_.alive).map { s =>
      Problem(s"server::${s.server}", s"Xatu on ${s.server} stopped reporting, its services and containers are not being checked")
    }

    targetProblems ++ downServers
  }

  case class Plan(send: Seq[Problem], resolved: Seq[(String, SentAlert)])

  /**
   * Which problems to send now: new ones, and the ones still happening every `repeatMillis`.
   * Alerts sent before and not a problem anymore are resolved.
   */
  def plan(current: Seq[Problem], sent: Map[String, SentAlert], now: Long, repeatMillis: Long): Plan = {
    val currentKeys = current.map(_.key).toSet
    Plan(
      send = current.filter(p => sent.get(p.key).forall(a => now - a.at >= repeatMillis)),
      resolved = sent.toSeq.filterNot { case (key, _) => currentKeys.contains(key) }.sortBy(_._1)
    )
  }
}
