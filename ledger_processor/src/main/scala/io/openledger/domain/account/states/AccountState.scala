package io.openledger.domain.account.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.DateUtils.TimeGen
import io.openledger.LedgerSerializable
import io.openledger.domain.account.Account.{AccountCommand, TransactionMessenger}
import io.openledger.events._

trait AccountState extends LedgerSerializable {
  def accountId: String

  def availableBalance: BigDecimal

  def currentBalance: BigDecimal

  def handleEvent(event: AccountEvent)(implicit context: ActorContext[AccountCommand]): AccountState

  def handleCommand(command: AccountCommand)(implicit context: ActorContext[AccountCommand], transactionMessenger: TransactionMessenger, now: TimeGen): Effect[AccountEvent, AccountState]
}
