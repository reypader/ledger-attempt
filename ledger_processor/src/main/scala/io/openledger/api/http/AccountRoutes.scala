package io.openledger.api.http

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorSystem, RecipientRef}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import io.openledger.AccountingMode
import io.openledger.AccountingMode.{CREDIT, DEBIT}
import io.openledger.api.http.Operations.{AccountResponse, AdjustRequest, Balance, OpenAccountRequest}
import io.openledger.domain.account.Account
import io.openledger.domain.account.Account.AccountCommand
import io.openledger.domain.account.states.{CreditAccount, DebitAccount, Ready}
import io.openledger.domain.entry.Entry
import io.openledger.domain.entry.Entry.{Adjust, EntryCommand}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

object AccountRoutes extends JsonSupport {
  implicit val httpRequestTimeout: Timeout = 15.seconds

  def apply(
      entryResolver: String => RecipientRef[EntryCommand],
      accountResolver: String => RecipientRef[AccountCommand]
  )(implicit system: ActorSystem[_], executionContext: ExecutionContext): Route =
    concat(
      pathEndOrSingleSlash {
        post {
          decodeRequest {
            entity(as[OpenAccountRequest]) { open =>
              val ref = accountResolver(open.account_id)
              onComplete(ref.ask(Account.Get)) {
                case Failure(exception) =>
                  failWith(exception)
                case Success(value) =>
                  value match {
                    case Ready(account_id) =>
                      ref ! Account.Open(open.account_type, open.accounting_tags)
                      complete(
                        StatusCodes.Accepted,
                        s"Account Opening request accepted. Account $account_id will be ready shortly."
                      )
                    case _ =>
                      complete(StatusCodes.Conflict, "Account already exists")
                  }
              }
            }
          }
        }
      },
      pathPrefix(Segment) { account_id =>
        concat(
          pathEndOrSingleSlash {
            get {
              onComplete(accountResolver(account_id).ask(Account.Get)) {
                case Failure(exception) =>
                  failWith(exception)
                case Success(value) =>
                  value match {
                    case CreditAccount(_, availableBalance, currentBalance, _, tags) =>
                      complete(
                        StatusCodes.OK,
                        AccountResponse(account_id, DEBIT, tags, Balance(availableBalance, currentBalance))
                      )
                    case DebitAccount(_, availableBalance, currentBalance, _, tags) =>
                      complete(
                        StatusCodes.OK,
                        AccountResponse(account_id, CREDIT, tags, Balance(availableBalance, currentBalance))
                      )
                    case _ =>
                      complete(StatusCodes.NotFound, "Account Not Found")
                  }
              }
            }
          },
          pathPrefix("adjustments") {
            pathEndOrSingleSlash {
              post {
                decodeRequest {
                  entity(as[AdjustRequest]) { adjust =>
                    onComplete(accountResolver(account_id).ask(Account.Get)) {
                      case Failure(exception) =>
                        failWith(exception)
                      case Success(value) =>
                        value match {
                          case _: CreditAccount | _: DebitAccount =>
                            adjust.adjustment_type match {
                              case DEBIT =>
                                onSuccess(
                                  entryResolver(adjust.entry_id)
                                    .ask(Adjust(adjust.entry_code, account_id, adjust.amount, AccountingMode.DEBIT, _))
                                ) {
                                  case Entry.Ack =>
                                    complete(
                                      StatusCodes.Accepted,
                                      "Adjustment accepted. Balance will be reflected shortly"
                                    )
                                  case Entry.Nack =>
                                    complete(
                                      StatusCodes.Conflict,
                                      "Adjustment cannot be done. Possible entry ID conflict"
                                    )
                                }

                              case CREDIT =>
                                onSuccess(
                                  entryResolver(adjust.entry_id)
                                    .ask(Adjust(adjust.entry_code, account_id, adjust.amount, AccountingMode.CREDIT, _))
                                ) {
                                  case Entry.Ack =>
                                    complete(
                                      StatusCodes.Accepted,
                                      "Adjustment accepted. Balance will be reflected shortly"
                                    )
                                  case Entry.Nack =>
                                    complete(
                                      StatusCodes.Conflict,
                                      "Adjustment cannot be done. Possible entry ID conflict"
                                    )
                                }
                              case _ => complete(StatusCodes.BadRequest, "Unknown adjustment type.")
                            }

                          case _ =>
                            complete(StatusCodes.NotFound, s"Account $account_id Not Opened")
                        }
                    }
                  }
                }
              }
            }
          }
        )
      }
    )
}
