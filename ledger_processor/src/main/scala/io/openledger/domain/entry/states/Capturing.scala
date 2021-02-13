package io.openledger.domain.entry.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.ResultingBalance
import io.openledger.domain.account.Account
import io.openledger.domain.entry.Entry
import io.openledger.domain.entry.Entry._
import io.openledger.events._

import java.time.OffsetDateTime

case class Capturing(
    entryCode: String,
    entryId: String,
    accountToDebit: String,
    accountToCredit: String,
    amountAuthorized: BigDecimal,
    captureAmount: BigDecimal,
    debitedAccountResultingBalance: ResultingBalance,
    creditedAccountResultingBalance: ResultingBalance,
    debitAuthorizeTimestamp: OffsetDateTime,
    reversalPending: Boolean
) extends PairedEntry {
  private val stateCommand =
    Account.DebitCapture(entryId, entryCode, captureAmount, amountAuthorized - captureAmount, debitAuthorizeTimestamp)

  override def handleEvent(
      event: EntryEvent
  )(implicit context: ActorContext[EntryCommand]): PartialFunction[EntryEvent, EntryState] = {
    case DebitCaptureSucceeded(debitCapturedAccountResultingBalance) =>
      Posted(
        entryCode,
        entryId,
        accountToDebit,
        accountToCredit,
        captureAmount,
        debitCapturedAccountResultingBalance,
        creditedAccountResultingBalance,
        reversalPending
      )
    case DebitCaptureFailed(_) => ResumableCapturing(this)
    case ReversalRequested()   => copy(reversalPending = true)

  }

  override def handleCommand(command: Entry.EntryCommand)(implicit
      context: ActorContext[EntryCommand],
      accountMessenger: AccountMessenger,
      resultMessenger: ResultMessenger
  ): PartialFunction[EntryCommand, Effect[EntryEvent, EntryState]] = {
    case AcceptAccounting(originalCommandHash, accountId, resultingBalance, timestamp)
        if accountId == accountToDebit && originalCommandHash == stateCommand.hashCode() =>
      Effect.persist(DebitCaptureSucceeded(resultingBalance)).thenRun(_.proceed())
    case RejectAccounting(originalCommandHash, accountId, code)
        if accountId == accountToDebit && originalCommandHash == stateCommand.hashCode() =>
      Effect
        .persist(DebitCaptureFailed(code.toString))
        .thenRun(_ => context.log.error(s"ALERT: Capturing failed $code for $accountToDebit."))
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
    context.log.info(s"Performing DebitCapture on $accountToDebit")
    accountMessenger(accountToDebit, stateCommand)
  }
}
