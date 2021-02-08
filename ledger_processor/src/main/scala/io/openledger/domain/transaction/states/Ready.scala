package io.openledger.domain.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.domain.transaction.Transaction
import io.openledger.domain.transaction.Transaction._
import io.openledger.events._

case class Ready(transactionId: String) extends TransactionState {
  override def handleEvent(event: TransactionEvent)(implicit context: ActorContext[TransactionCommand]): PartialFunction[TransactionEvent, TransactionState] = {
    case Started(entryCode, accountToDebit, accountToCredit, amount, authOnly) => Authorizing(entryCode, transactionId, accountToDebit, accountToCredit, amount, authOnly)
    case AdjustRequested(entryCode, accountToAdjust, amount, mode) => Adjusting(entryCode, transactionId, accountToAdjust, amount, mode)
  }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): PartialFunction[TransactionCommand, Effect[TransactionEvent, TransactionState]] = {
    case Begin(entryCode, accountToDebit, accountToCredit, amount, replyTo, authOnly) =>
      Effect.persist(Started(entryCode, accountToDebit, accountToCredit, amount, authOnly))
        .thenRun { next: TransactionState =>
          next.proceed()
          replyTo ! Ack
        }
    case Adjust(entryCode, accountToAdjust, amount, mode, replyTo) =>
      Effect.persist(AdjustRequested(entryCode, accountToAdjust, amount, mode))
        .thenRun { next: TransactionState =>
          next.proceed()
          replyTo ! Ack
        }
  }


  override def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    context.log.info(s"Doing nothing on Ready")
  }
}
