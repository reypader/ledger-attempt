package io.openledger.domain.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.domain.transaction.Transaction
import io.openledger.domain.transaction.Transaction._
import io.openledger.events.TransactionEvent
import io.openledger.{LedgerError, ResultingBalance}

case class Reversed(entryCode: String, transactionId: String, debitReversedResultingBalance: ResultingBalance, creditReversedResultingBalance: Option[ResultingBalance]) extends TransactionState {
  override def handleEvent(event: TransactionEvent)(implicit context: ActorContext[TransactionCommand]): TransactionState = this

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Effect[TransactionEvent, TransactionState] = {
    command match {
      case ackable: Ackable =>
        context.log.warn(s"Unhandled Ackable $command in Reversed. NACK")
        Effect.none
          .thenRun { _ =>
            ackable.replyTo ! Nack
            resultMessenger(CommandRejected(transactionId, LedgerError.UNSUPPORTED_OPERATION))
          }
      case _ =>
        context.log.warn(s"Unhandled $command in Reversed")
        Effect.none
    }
  }

  override def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    context.log.info(s"Announcing result on Reversed")
    resultMessenger(TransactionReversed(transactionId, debitReversedResultingBalance, creditReversedResultingBalance))
  }
}
