package io.openledger.account

import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import io.openledger.account.AccountMode.AccountMode
import io.openledger.account.states.{AccountState, Active}
import io.openledger.{JsonSerializable, LedgerError}

import java.util.UUID

object Account {

  sealed trait AccountCommand extends JsonSerializable

  final case class Debit(amountToDebit: BigDecimal, replyTo: ActorRef[AdjustmentStatus]) extends AccountCommand

  final case class DebitHold(amountToDebit: BigDecimal, replyTo: ActorRef[AdjustmentStatus]) extends AccountCommand

  final case class Credit(amountToCredit: BigDecimal, replyTo: ActorRef[AdjustmentStatus]) extends AccountCommand

  final case class CreditHold(amountToCredit: BigDecimal, replyTo: ActorRef[AdjustmentStatus]) extends AccountCommand

  sealed trait AdjustmentStatus extends AccountCommand

  final case class AdjustmentSuccessful(availableBalance: BigDecimal, currentBalance: BigDecimal, reservedBalance: BigDecimal) extends AdjustmentStatus

  final case class AdjustmentFailed(code: LedgerError.Value) extends AdjustmentStatus


  sealed trait AccountEvent extends JsonSerializable

  final case class Debited(newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal) extends AccountEvent

  final case class DebitHeld(newAvailableBalance: BigDecimal, newReservedBalance : BigDecimal) extends AccountEvent

  final case class Credited(newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal) extends AccountEvent

  final case class CreditHeld(newAvailableBalance: BigDecimal, newReservedBalance:BigDecimal) extends AccountEvent

  def apply(accountId: UUID, mode: AccountMode): Behavior[AccountCommand] =
    EventSourcedBehavior[AccountCommand, AccountEvent, AccountState](
      persistenceId = PersistenceId.ofUniqueId(accountId.toString),
      emptyState = Active(mode, BigDecimal(0), BigDecimal(0), BigDecimal(0)),
      commandHandler = (state, cmd) => state.handleCommand(cmd),
      eventHandler = (state, evt) => state.handleEvent(evt))

}
