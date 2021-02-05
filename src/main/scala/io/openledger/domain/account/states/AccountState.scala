package io.openledger.domain.account.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.DateUtils.TimeGen
import io.openledger.JsonSerializable
import io.openledger.domain.account.Account.{AccountCommand, AccountEvent, TransactionMessenger}

trait AccountState extends JsonSerializable {
  def handleEvent(event: AccountEvent)(implicit context: ActorContext[AccountCommand]): AccountState

  def handleCommand(command: AccountCommand)(implicit context: ActorContext[AccountCommand], transactionMessenger: TransactionMessenger, now: TimeGen): Effect[AccountEvent, AccountState]
}
