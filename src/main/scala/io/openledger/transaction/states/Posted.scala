package io.openledger.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.ResultingBalance
import io.openledger.transaction.Transaction
import io.openledger.transaction.Transaction._

case class Posted(transactionId: String, accountToDebit: String, accountToCredit: String, amountCaptured: BigDecimal, debitedAccountResultingBalance: ResultingBalance, creditedAccountResultingBalance: ResultingBalance) extends TransactionState {
  override def handleEvent(event: Transaction.TransactionEvent)(implicit context: ActorContext[TransactionCommand]): TransactionState =
    event match {
      case ReversalRequested() => RollingBackCredit(transactionId, accountToDebit, accountToCredit, amountCaptured, Some(amountCaptured), None)
    }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Effect[Transaction.TransactionEvent, TransactionState] = {
    context.log.info(s"Handling $command")
    command match {
      case Reverse() => Effect.persist(ReversalRequested()).thenRun(_.proceed())
      case _=>
        context.log.warn(s"Unhandled $command")
        Effect.none
    }
  }

  override def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    context.log.info(s"Announcing result on Posted")
    resultMessenger(TransactionSuccessful(debitedAccountResultingBalance, creditedAccountResultingBalance))
  }
}
