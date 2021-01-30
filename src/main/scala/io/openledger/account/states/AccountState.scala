package io.openledger.account.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.JsonSerializable
import io.openledger.account.Account.{AccountCommand, AccountEvent, TransactionMessenger}

trait AccountState extends JsonSerializable {
  def handleEvent(event: AccountEvent)(implicit context: ActorContext[AccountCommand]): AccountState

  def handleCommand(command: AccountCommand)(implicit context: ActorContext[AccountCommand], transactionMessenger: TransactionMessenger): Effect[AccountEvent, AccountState]
}
