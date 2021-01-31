package io.openledger.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.transaction.Transaction
import io.openledger.transaction.Transaction._

case class Ready(transactionId: String) extends TransactionState {
  override def handleEvent(event: Transaction.TransactionEvent)(implicit context: ActorContext[TransactionCommand]): TransactionState =
    event match {
      case Started(accountToDebit, accountToCredit, amount, authOnly) => Authorizing(transactionId, accountToDebit, accountToCredit, amount, authOnly)
    }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Effect[Transaction.TransactionEvent, TransactionState] = {
    context.log.info(s"Handling command $command")
    command match {
      case Begin(accountToDebit, accountToCredit, amount, authOnly) =>
        Effect.persist(Started(accountToDebit, accountToCredit, amount, authOnly))
          .thenRun(_.proceed())
    }
  }


  override def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    context.log.info(s"Doing nothing on Ready")
  }
}
