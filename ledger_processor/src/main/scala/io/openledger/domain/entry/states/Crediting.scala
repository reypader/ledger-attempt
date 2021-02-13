package io.openledger.domain.entry.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.ResultingBalance
import io.openledger.domain.account.Account
import io.openledger.domain.entry.Entry
import io.openledger.domain.entry.Entry._
import io.openledger.events._

import java.time.OffsetDateTime

case class Crediting(
    entryCode: String,
    entryId: String,
    accountToDebit: String,
    accountToCredit: String,
    amountAuthorized: BigDecimal,
    captureAmount: BigDecimal,
    debitedAccountResultingBalance: ResultingBalance,
    debitHoldTimestamp: OffsetDateTime,
    reversalPending: Boolean
) extends PairedEntry {
  private val stateCommand = Account.Credit(entryId, entryCode, captureAmount)

  override def handleEvent(
      event: EntryEvent
  )(implicit context: ActorContext[EntryCommand]): PartialFunction[EntryEvent, EntryState] = {
    case CreditSucceeded(creditedAccountResultingBalance) =>
      Posting(
        entryCode,
        entryId,
        accountToDebit,
        accountToCredit,
        amountAuthorized,
        captureAmount,
        debitedAccountResultingBalance,
        creditedAccountResultingBalance,
        debitHoldTimestamp,
        reversalPending
      )
    case CreditFailed(code) =>
      RollingBackDebit(entryCode, entryId, accountToDebit, accountToCredit, amountAuthorized, None, Some(code), None)
    case ReversalRequested() => copy(reversalPending = true)
  }

  override def handleCommand(command: Entry.EntryCommand)(implicit
      context: ActorContext[EntryCommand],
      accountMessenger: AccountMessenger,
      resultMessenger: ResultMessenger
  ): PartialFunction[EntryCommand, Effect[EntryEvent, EntryState]] = {
    case AcceptAccounting(originalCommandHash, accountId, resultingBalance, _)
        if accountId == accountToCredit && originalCommandHash == stateCommand.hashCode() =>
      Effect.persist(CreditSucceeded(resultingBalance)).thenRun(_.proceed())
    case RejectAccounting(originalCommandHash, accountId, code)
        if accountId == accountToCredit && originalCommandHash == stateCommand.hashCode() =>
      Effect.persist(CreditFailed(code.toString)).thenRun(_.proceed())
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
    context.log.info(s"Performing Credit on $accountToCredit")
    accountMessenger(accountToCredit, stateCommand)
  }
}
