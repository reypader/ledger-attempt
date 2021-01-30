package io.openledger.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.transaction.Transaction
import io.openledger.transaction.Transaction.{AccountMessenger, Begin, Started, TransactionCommand}

case class Ready(transactionId: String) extends TransactionState {
  override def handleEvent(event: Transaction.TransactionEvent)(implicit context: ActorContext[TransactionCommand]): TransactionState =
    event match {
      case Started(accountToDebit, accountToCredit, amount) => Debiting(transactionId, accountToDebit, accountToCredit, amount)
    }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand],accountMessenger: AccountMessenger): Effect[Transaction.TransactionEvent, TransactionState] =
    command match {
      case Begin(accountToDebit, accountToCredit, amount) =>
        Effect.persist(Started(accountToDebit, accountToCredit, amount))
          .thenRun(_.proceed())
    }

  override def proceed()(implicit context: ActorContext[TransactionCommand],accountMessenger: AccountMessenger): Unit = {
    //Does nothing
  }
}
