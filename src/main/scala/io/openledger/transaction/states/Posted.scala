package io.openledger.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.account.Account
import io.openledger.account.Account.AccountingSuccessful
import io.openledger.transaction.Transaction
import io.openledger.transaction.Transaction.{AccountMessenger, AccountingResult, CreditSucceeded, TransactionCommand}

case class Posted() extends TransactionState {
  override def handleEvent(event: Transaction.TransactionEvent)(implicit context: ActorContext[TransactionCommand]): TransactionState = ???

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand],accountMessenger: AccountMessenger): Effect[Transaction.TransactionEvent, TransactionState] = ???

  override def proceed()(implicit context: ActorContext[TransactionCommand],accountMessenger: AccountMessenger): Unit = {
  }
}
