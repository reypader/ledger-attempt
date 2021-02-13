package io.openledger.domain.entry.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.ResultingBalance
import io.openledger.domain.account.Account
import io.openledger.domain.entry.Entry
import io.openledger.domain.entry.Entry._
import io.openledger.events._

import java.time.OffsetDateTime

case class Posting(
    entryCode: String,
    entryId: String,
    accountToDebit: String,
    accountToCredit: String,
    amountAuthorized: BigDecimal,
    captureAmount: BigDecimal,
    debitedAccountResultingBalance: ResultingBalance,
    creditedAccountResultingBalance: ResultingBalance,
    debitHoldTimestamp: OffsetDateTime,
    reversalPending: Boolean,
    failedPosting: Boolean = false
) extends PairedEntry {
  private val stateCommand =
    Account.Post(entryId, entryCode, captureAmount, amountAuthorized - captureAmount, debitHoldTimestamp)

  override def handleEvent(
      event: EntryEvent
  )(implicit context: ActorContext[EntryCommand]): PartialFunction[EntryEvent, EntryState] = {
    case DebitPostSucceeded(debitPostedAccountResultingBalance) =>
      Posted(
        entryCode,
        entryId,
        accountToDebit,
        accountToCredit,
        captureAmount,
        debitPostedAccountResultingBalance,
        creditedAccountResultingBalance,
        reversalPending
      )
    case DebitPostFailed(_)  => ResumablePosting(this)
    case ReversalRequested() => copy(reversalPending = true)

  }

  override def handleCommand(command: Entry.EntryCommand)(implicit
      context: ActorContext[EntryCommand],
      accountMessenger: AccountMessenger,
      resultMessenger: ResultMessenger
  ): PartialFunction[EntryCommand, Effect[EntryEvent, EntryState]] = {
    case AcceptAccounting(originalCommandHash, accountId, resultingBalance, timestamp)
        if accountId == accountToDebit && originalCommandHash == stateCommand.hashCode() =>
      Effect.persist(DebitPostSucceeded(resultingBalance)).thenRun(_.proceed())
    case RejectAccounting(originalCommandHash, accountId, code)
        if accountId == accountToDebit && originalCommandHash == stateCommand.hashCode() =>
      Effect
        .persist(DebitPostFailed(code.toString))
        .thenRun(_ => context.log.error(s"ALERT: Posting failed $code for $accountToDebit."))
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
    context.log.info(s"Performing Post on $accountToDebit")
    accountMessenger(accountToDebit, stateCommand)
  }
}
