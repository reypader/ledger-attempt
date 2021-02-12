package io.openledger.setup

import akka.actor.CoordinatedShutdown
import akka.actor.typed.{ActorSystem, RecipientRef}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import io.openledger.api.http.{AccountRoutes, JsonSupport}
import io.openledger.domain.account.Account.AccountCommand
import io.openledger.domain.entry.Entry.EntryCommand

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

object HttpServerSetup {
  def apply(
      coordinatedShutdown: CoordinatedShutdown,
      entryResolver: String => RecipientRef[EntryCommand],
      accountResolver: String => RecipientRef[AccountCommand]
  )(implicit system: ActorSystem[_], executionContext: ExecutionContext) =
    new HttpServerSetup(coordinatedShutdown, entryResolver, accountResolver)
}

class HttpServerSetup(
    coordinatedShutdown: CoordinatedShutdown,
    entryResolver: String => RecipientRef[EntryCommand],
    accountResolver: String => RecipientRef[AccountCommand]
)(implicit system: ActorSystem[_], executionContext: ExecutionContext)
    extends JsonSupport {
  implicit val httpRequestTimeout: Timeout = 15.seconds

  val accountRoutes: Route = AccountRoutes(entryResolver, accountResolver)

  val route: Route =
    concat(
      pathPrefix("accounts")(accountRoutes)
    )

  def run(): Unit = {
    val bindingFuture = Http().newServerAt("0.0.0.0", 8080).bind(route)

    coordinatedShutdown.addTask(CoordinatedShutdown.PhaseServiceUnbind, "shutdown-http-server") { () =>
      bindingFuture.flatMap(_.unbind())
    }
  }
}
