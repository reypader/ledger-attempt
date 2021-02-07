package io.openledger.domain.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.LedgerError
import io.openledger.domain.transaction.Transaction
import io.openledger.domain.transaction.Transaction.{AccountMessenger, ResultMessenger, TransactionCommand, TransactionFailed}
import io.openledger.events.TransactionEvent

case class Failed(entryCode: String, transactionId: String, accountToDebit: String, accountToCredit: String, amount: BigDecimal, code: String) extends TransactionState {
  override def handleEvent(event: TransactionEvent)(implicit context: ActorContext[TransactionCommand]): TransactionState = this

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Effect[TransactionEvent, TransactionState] = Effect.none

  override def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    context.log.info(s"Announcing result on Failed")
    resultMessenger(TransactionFailed(transactionId, LedgerError.withName(code)))
  }
}
