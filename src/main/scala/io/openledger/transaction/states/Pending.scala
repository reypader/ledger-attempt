package io.openledger.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.ResultingBalance
import io.openledger.transaction.Transaction
import io.openledger.transaction.Transaction._

import java.time.OffsetDateTime

case class Pending(transactionId: String, accountToDebit: String, accountToCredit: String, amountAuthorized: BigDecimal, debitedAccountResultingBalance: ResultingBalance, debitHoldTimestamp: OffsetDateTime) extends TransactionState {
  override def handleEvent(event: Transaction.TransactionEvent)(implicit context: ActorContext[TransactionCommand]): TransactionState =
    event match {
      case CaptureRequested(captureAmount) => Crediting(transactionId, accountToDebit, accountToCredit, amountAuthorized, captureAmount, debitedAccountResultingBalance, debitHoldTimestamp)
      case ReversalRequested() => RollingBackDebit(transactionId, accountToDebit, accountToCredit, amountAuthorized, None, None)
    }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Effect[Transaction.TransactionEvent, TransactionState] =
    command match {
      case Capture(captureAmount) =>
        Effect.persist(CaptureRequested(captureAmount)).thenRun(_.proceed())
      case Reverse() =>
        Effect.persist(ReversalRequested()).thenRun(_.proceed())
    }

  override def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    context.log.info(s"Awaiting Capture on Pending")
    resultMessenger(TransactionPending())
  }
}
