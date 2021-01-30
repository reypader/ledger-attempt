package io.openledger.transaction

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import io.openledger.JsonSerializable
import io.openledger.account.Account.{AccountingCommand, AccountingStatus}
import io.openledger.transaction.states.{Ready, TransactionState}

object Transaction {

  type AccountMessenger = (String, AccountingCommand) => Unit

  def apply(transactionId: String)(implicit accountMessenger: AccountMessenger): Behavior[TransactionCommand] =
    Behaviors.setup { implicit actorContext: ActorContext[TransactionCommand] =>
      EventSourcedBehavior[TransactionCommand, TransactionEvent, TransactionState](
        persistenceId = PersistenceId.ofUniqueId(transactionId),
        emptyState = Ready(transactionId),
        commandHandler = (state, cmd) => state.handleCommand(cmd),
        eventHandler = (state, evt) => state.handleEvent(evt))
    }

  sealed trait TransactionResult extends JsonSerializable

  sealed trait TransactionEvent extends JsonSerializable

  sealed trait TransactionCommand extends JsonSerializable

  final case class Begin(accountToDebit: String, accountToCredit: String, amount: BigDecimal) extends TransactionCommand

  final case class AccountingResult(accounting: AccountingStatus) extends TransactionCommand

  final case class Started(accountToDebit: String, accountToCredit: String, amount: BigDecimal) extends TransactionEvent

  final case class DebitSucceeded() extends TransactionEvent

  final case class CreditSucceeded() extends TransactionEvent


}
