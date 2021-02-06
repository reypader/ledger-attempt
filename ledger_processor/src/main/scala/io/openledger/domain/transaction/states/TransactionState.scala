package io.openledger.domain.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.LedgerSerializable
import io.openledger.domain.transaction.Transaction.{AccountMessenger, ResultMessenger, TransactionCommand}
import io.openledger.events._

trait TransactionState extends LedgerSerializable {
  def handleEvent(event: TransactionEvent)(implicit context: ActorContext[TransactionCommand]): TransactionState

  def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit

  def handleCommand(command: TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Effect[TransactionEvent, TransactionState]
}

