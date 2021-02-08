package io.openledger

import akka.actor.CoordinatedShutdown
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorSystem, RecipientRef}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import io.openledger.domain.account.Account
import io.openledger.domain.account.Account.AccountCommand
import io.openledger.domain.account.states.{CreditAccount, DebitAccount, Ready}
import io.openledger.domain.transaction.Transaction
import io.openledger.domain.transaction.Transaction.{Adjust, TransactionCommand}
import io.openledger.http_operations.AccountResponse.Balance
import io.openledger.http_operations.{AccountResponse, AdjustRequest, OpenAccountRequest, Type}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

class HttpServerSetup(coordinatedShutdown: CoordinatedShutdown, transactionResolver: String => RecipientRef[TransactionCommand], accountResolver: String => RecipientRef[AccountCommand])(implicit system: ActorSystem[_], executionContext: ExecutionContext) extends JsonSupport {
  implicit val httpRequestTimeout: Timeout = 15.seconds

  val accountRoutes: Route =
    concat {
      pathEnd {
        post {
          decodeRequest {
            entity(as[OpenAccountRequest]) { open =>
              val ref = accountResolver(open.accountId)
              onComplete(ref.ask(Account.Get)) {
                case Failure(exception) =>
                  failWith(exception)
                case Success(value) => value match {
                  case Ready(accountId) =>
                    ref ! Account.Open(AccountingMode.withName(open.accountType.name), open.accountingTags.toSet)
                    complete(StatusCodes.Accepted, s"Account Opening request accepted. Account $accountId will be ready shortly.")
                  case _ =>
                    complete(StatusCodes.Conflict, "Account already exists")
                }
              }
            }
          }
        }
      }
      path(Segment) { accountId =>
        concat {
          get {
            onComplete(accountResolver(accountId).ask(Account.Get)) {
              case Failure(exception) =>
                failWith(exception)
              case Success(value) => value match {
                case CreditAccount(_, availableBalance, currentBalance, _, tags) =>
                  complete(StatusCodes.OK, AccountResponse(accountId, Type.DEBIT, Some(Balance(availableBalance.doubleValue, currentBalance.doubleValue)), tags.toSeq))
                case DebitAccount(_, availableBalance, currentBalance, _, tags) =>
                  complete(StatusCodes.OK, AccountResponse(accountId, Type.CREDIT, Some(Balance(availableBalance.doubleValue, currentBalance.doubleValue)), tags.toSeq))
                case _ =>
                  complete(StatusCodes.NotFound, "Account Not Found")
              }
            }
          }
          path("adjustments") {
            post {
              decodeRequest {
                entity(as[AdjustRequest]) { adjust =>
                  onComplete(accountResolver(accountId).ask(Account.Get)) {
                    case Failure(exception) =>
                      failWith(exception)
                    case Success(value) => value match {
                      case _: CreditAccount | _: DebitAccount =>
                        adjust.adjustmentType match {
                          case Type.DEBIT =>
                            onSuccess(transactionResolver(adjust.transactionId).ask(Adjust(adjust.entryCode, accountId, adjust.amount, AccountingMode.DEBIT, _))) {
                              case Transaction.Ack => complete(StatusCodes.Accepted, "Adjustment accepted. Balance will be reflected shortly")
                              case Transaction.Nack => complete(StatusCodes.Conflict, "Adjustment cannot be done. Possible transaction ID conflict")
                            }

                          case Type.CREDIT =>
                            onSuccess(transactionResolver(adjust.transactionId).ask(Adjust(adjust.entryCode, accountId, adjust.amount, AccountingMode.CREDIT, _))) {
                              case Transaction.Ack => complete(StatusCodes.Accepted, "Adjustment accepted. Balance will be reflected shortly")
                              case Transaction.Nack => complete(StatusCodes.Conflict, "Adjustment cannot be done. Possible transaction ID conflict")
                            }
                          case _ => complete(StatusCodes.BadRequest, "Unknown adjustment type.")
                        }

                      case _ =>
                        complete(StatusCodes.NotFound, s"Account $accountId Not Opened")
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

  val route: Route =
    concat {
      path("accounts")(accountRoutes)
    }


  def run(): Unit = {
    val bindingFuture = Http().newServerAt("localhost", 8080).bind(route)

    coordinatedShutdown.addTask(CoordinatedShutdown.PhaseServiceUnbind, "shutdown-http-server") { () =>
      bindingFuture.flatMap(_.unbind())
    }
  }
}
