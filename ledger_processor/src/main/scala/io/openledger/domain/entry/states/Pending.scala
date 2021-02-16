package io.openledger.domain.entry.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.domain.entry.Entry
import io.openledger.domain.entry.Entry._
import io.openledger.events._
import io.openledger.{DateUtils, LedgerError, ResultingBalance}

import java.time.OffsetDateTime

case class Pending(
    entryCode: String,
    entryId: String,
    accountToDebit: String,
    accountToCredit: String,
    amountAuthorized: BigDecimal,
    debitedAccountResultingBalance: ResultingBalance,
    debitAuthorizeTimestamp: OffsetDateTime,
    reversalPending: Boolean
) extends PairedEntry {
  override def handleEvent(
      event: EntryEvent
  )(implicit context: ActorContext[EntryCommand]): PartialFunction[EntryEvent, EntryState] = {
    case CaptureRequested(captureAmount) =>
      Crediting(
        entryCode,
        entryId,
        accountToDebit,
        accountToCredit,
        amountAuthorized,
        captureAmount,
        debitedAccountResultingBalance,
        debitAuthorizeTimestamp,
        reversalPending
      )
    case ReversalRequested(_) =>
      RollingBackDebit(entryCode, entryId, accountToDebit, accountToCredit, amountAuthorized, None, None, None)
    case Suspended(_) => this
  }

  override def handleCommand(command: Entry.EntryCommand)(implicit
      context: ActorContext[EntryCommand],
      accountMessenger: AccountMessenger,
      resultMessenger: ResultMessenger
  ): PartialFunction[EntryCommand, Effect[EntryEvent, EntryState]] = {
    case Capture(captureAmount, replyTo) if captureAmount <= amountAuthorized =>
      Effect
        .persist(CaptureRequested(captureAmount))
        .thenRun { next: EntryState =>
          next.proceed()
          replyTo ! Ack
        }
    case Capture(captureAmount, replyTo) if captureAmount > amountAuthorized =>
      Effect.none
        .thenRun { _: EntryState =>
          resultMessenger(CaptureRejected(entryId, LedgerError.CAPTURE_MORE_THAN_AUTHORIZED))
          replyTo ! Ack
        }
    case Reverse(replyTo) =>
      Effect
        .persist(ReversalRequested(DateUtils.now()))
        .thenRun { next: EntryState =>
          next.proceed()
          replyTo ! Ack
        }
  }

  override def proceed()(implicit
      context: ActorContext[EntryCommand],
      accountMessenger: AccountMessenger,
      resultMessenger: ResultMessenger
  ): Unit = {
    context.log.info(s"Awaiting DebitCapture on Pending")
    resultMessenger(EntryPending(entryId, debitedAccountResultingBalance))
  }
}
