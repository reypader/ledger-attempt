package io.openledger.domain.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.domain.transaction.Transaction
import io.openledger.domain.transaction.Transaction._
import io.openledger.events._
import io.openledger.{LedgerError, ResultingBalance}

import java.time.OffsetDateTime

case class Pending(entryCode: String, transactionId: String, accountToDebit: String, accountToCredit: String, amountAuthorized: BigDecimal, debitedAccountResultingBalance: ResultingBalance, debitHoldTimestamp: OffsetDateTime,reversalPending:Boolean) extends TransactionState {
  override def handleEvent(event: TransactionEvent)(implicit context: ActorContext[TransactionCommand]): PartialFunction[TransactionEvent, TransactionState] = {
    case CaptureRequested(captureAmount) => Crediting(entryCode, transactionId, accountToDebit, accountToCredit, amountAuthorized, captureAmount, debitedAccountResultingBalance, debitHoldTimestamp,reversalPending)
    case ReversalRequested() => RollingBackDebit(entryCode, transactionId, accountToDebit, accountToCredit, amountAuthorized, None, None, None)
  }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): PartialFunction[TransactionCommand, Effect[TransactionEvent, TransactionState]] = {
    case Capture(captureAmount, replyTo) if captureAmount <= amountAuthorized =>
      Effect.persist(CaptureRequested(captureAmount))
        .thenRun { next: TransactionState =>
          next.proceed()
          replyTo ! Ack
        }
    case Capture(captureAmount, replyTo) if captureAmount > amountAuthorized =>
      Effect.none
        .thenRun { _: TransactionState =>
          resultMessenger(CaptureRejected(transactionId, LedgerError.CAPTURE_MORE_THAN_AUTHORIZED))
          replyTo ! Ack
        }
    case Reverse(replyTo) =>
      Effect.persist(ReversalRequested())
        .thenRun { next: TransactionState =>
          next.proceed()
          replyTo ! Ack
        }
  }

  override def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    context.log.info(s"Awaiting Capture on Pending")
    resultMessenger(TransactionPending(transactionId, debitedAccountResultingBalance))

    if (reversalPending){
      context.log.info(s"Reversal marked on Pending state. Triggering self-reversal")
      context.self ! Reverse(context.system.ignoreRef)
    }
  }
}
