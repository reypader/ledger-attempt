package io.openledger.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.{LedgerError, ResultingBalance}
import io.openledger.transaction.Transaction
import io.openledger.transaction.Transaction._

import java.time.OffsetDateTime

case class Pending(transactionId: String, accountToDebit: String, accountToCredit: String, amountAuthorized: BigDecimal, debitedAccountResultingBalance: ResultingBalance, debitHoldTimestamp: OffsetDateTime) extends TransactionState {
  override def handleEvent(event: Transaction.TransactionEvent)(implicit context: ActorContext[TransactionCommand]): TransactionState =
    event match {
      case CaptureRequested(captureAmount) => Crediting(transactionId, accountToDebit, accountToCredit, amountAuthorized, captureAmount, debitedAccountResultingBalance, debitHoldTimestamp)
      case ReversalRequested() => RollingBackDebit(transactionId, accountToDebit, accountToCredit, amountAuthorized, None, None)
    }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Effect[Transaction.TransactionEvent, TransactionState] = {
    context.log.info(s"Handling $command")
    command match {
      case Capture(captureAmount) if captureAmount <= amountAuthorized =>
        Effect.persist(CaptureRequested(captureAmount)).thenRun(_.proceed())
      case Capture(captureAmount) if captureAmount > amountAuthorized =>
        Effect.none.thenRun(_=>resultMessenger(CaptureRejected(LedgerError.CAPTURE_MORE_THAN_AUTHORIZED)))
      case Reverse() =>
        Effect.persist(ReversalRequested()).thenRun(_.proceed())
      case _=>
        context.log.warn(s"Unhandled $command")
        Effect.none
    }
  }

  override def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    context.log.info(s"Awaiting Capture on Pending")
    resultMessenger(TransactionPending())
  }
}
