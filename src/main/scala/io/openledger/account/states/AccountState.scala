package io.openledger.account.states

import akka.persistence.typed.scaladsl.ReplyEffect
import io.openledger.JsonSerializable
import io.openledger.account.Account.{AccountCommand, AccountEvent}

trait AccountState extends JsonSerializable {
  def handleEvent(event: AccountEvent): AccountState

  def handleCommand(command: AccountCommand): ReplyEffect[AccountEvent, AccountState]
}
