package io.openledger.domain.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.domain.transaction.Transaction
import io.openledger.domain.transaction.Transaction._
import io.openledger.events._
import io.openledger.{LedgerError, ResultingBalance}

case class Posted(entryCode: String, transactionId: String, accountToDebit: String, accountToCredit: String, amountCaptured: BigDecimal, debitedAccountResultingBalance: ResultingBalance, creditedAccountResultingBalance: ResultingBalance) extends TransactionState {
  override def handleEvent(event: TransactionEvent)(implicit context: ActorContext[TransactionCommand]): TransactionState =
    event match {
      case ReversalRequested() => RollingBackCredit(entryCode, transactionId, accountToDebit, accountToCredit, amountCaptured, Some(amountCaptured), None)
    }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Effect[TransactionEvent, TransactionState] = {
    context.log.info(s"Handling $command in Posted")
    command match {
      case Reverse(replyTo) => Effect.persist(ReversalRequested())
        .thenRun { next: TransactionState =>
          next.proceed()
          replyTo ! Ack
        }
      case ackable: Ackable =>
        context.log.warn(s"Unhandled Ackable $command in Posted. NACK")
        Effect.none
          .thenRun { _ =>
            ackable.replyTo ! Nack
            resultMessenger(CommandRejected(transactionId, LedgerError.UNSUPPORTED_OPERATION))
          }
      case _ =>
        context.log.warn(s"Unhandled $command in Posted")
        Effect.none
    }
  }

  override def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    context.log.info(s"Announcing result on Posted")
    resultMessenger(TransactionSuccessful(transactionId, debitedAccountResultingBalance, creditedAccountResultingBalance))
  }
}
