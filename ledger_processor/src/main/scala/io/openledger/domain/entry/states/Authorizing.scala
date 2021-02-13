package io.openledger.domain.entry.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.domain.account.Account
import io.openledger.domain.entry.Entry
import io.openledger.domain.entry.Entry._
import io.openledger.events._

case class Authorizing(
    entryCode: String,
    entryId: String,
    accountToDebit: String,
    accountToCredit: String,
    amountAuthorized: BigDecimal,
    authOnly: Boolean,
    reversalPending: Boolean
) extends PairedEntry {
  private val stateCommand = Account.DebitAuthorize(entryId, entryCode, amountAuthorized)

  override def handleEvent(
      event: EntryEvent
  )(implicit context: ActorContext[EntryCommand]): PartialFunction[EntryEvent, EntryState] = {
    case DebitAuthorizeSucceeded(debitedAccountResultingBalance, timestamp) =>
      if (authOnly) {
        Pending(
          entryCode,
          entryId,
          accountToDebit,
          accountToCredit,
          amountAuthorized,
          debitedAccountResultingBalance,
          timestamp,
          reversalPending
        )
      } else {
        Crediting(
          entryCode,
          entryId,
          accountToDebit,
          accountToCredit,
          amountAuthorized,
          amountAuthorized,
          debitedAccountResultingBalance,
          timestamp,
          reversalPending
        )
      }
    case DebitAuthorizeFailed(code) => Failed(entryCode, entryId, code)
    case ReversalRequested()        => copy(reversalPending = true)
  }

  override def handleCommand(command: Entry.EntryCommand)(implicit
      context: ActorContext[EntryCommand],
      accountMessenger: AccountMessenger,
      resultMessenger: ResultMessenger
  ): PartialFunction[EntryCommand, Effect[EntryEvent, EntryState]] = {
    case AcceptAccounting(originalCommandHash, accountId, resultingBalance, timestamp)
        if accountId == accountToDebit && originalCommandHash == stateCommand.hashCode() =>
      Effect.persist(DebitAuthorizeSucceeded(resultingBalance, timestamp)).thenRun(_.proceed())
    case RejectAccounting(originalCommandHash, accountId, code)
        if accountId == accountToDebit && originalCommandHash == stateCommand.hashCode() =>
      Effect.persist(DebitAuthorizeFailed(code.toString)).thenRun(_.proceed())
    case Reverse(replyTo) =>
      Effect
        .persist(ReversalRequested())
        .thenRun { next: EntryState =>
          replyTo ! Ack
        }
  }

  override def proceed()(implicit
      context: ActorContext[EntryCommand],
      accountMessenger: AccountMessenger,
      resultMessenger: ResultMessenger
  ): Unit = {
    context.log.info(s"Performing DebitAuthorize on $accountToDebit")
    accountMessenger(accountToDebit, stateCommand)
  }
}
