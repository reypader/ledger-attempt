package io.openledger.account

import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import io.openledger.account.AccountMode.AccountMode
import io.openledger.account.states.{AccountState, CreditAccount}
import io.openledger.{JsonSerializable, LedgerError}

import java.util.UUID

object Account {

  sealed trait AccountCommand extends JsonSerializable

  final case class Debit(amountToDebit: BigDecimal, replyTo: ActorRef[AdjustmentStatus]) extends AccountCommand

  final case class Credit(amountToCredit: BigDecimal, replyTo: ActorRef[AdjustmentStatus]) extends AccountCommand

  final case class Hold(amountToHold: BigDecimal, replyTo: ActorRef[AdjustmentStatus]) extends AccountCommand

  final case class Capture(amountToCapture: BigDecimal, amountToRelease:BigDecimal, replyTo: ActorRef[AdjustmentStatus]) extends AccountCommand

  sealed trait AdjustmentStatus extends AccountCommand

  final case class AdjustmentSuccessful(availableBalance: BigDecimal, currentBalance: BigDecimal, authorizedBalance: BigDecimal) extends AdjustmentStatus

  final case class AdjustmentFailed(code: LedgerError.Value) extends AdjustmentStatus


  sealed trait AccountEvent extends JsonSerializable

  final case class Debited(newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal) extends AccountEvent

  final case class Credited(newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal) extends AccountEvent

  final case class Authorized(newAvailableBalance: BigDecimal, newAuthorizedBalance: BigDecimal) extends AccountEvent

  final case class Captured(newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal,newAuthorizedBalance: BigDecimal) extends AccountEvent

  def apply(accountId: UUID, mode: AccountMode): Behavior[AccountCommand] =
    EventSourcedBehavior[AccountCommand, AccountEvent, AccountState](
      persistenceId = PersistenceId.ofUniqueId(accountId.toString),
      emptyState = CreditAccount(BigDecimal(0), BigDecimal(0), BigDecimal(0)),
      commandHandler = (state, cmd) => state.handleCommand(cmd),
      eventHandler = (state, evt) => state.handleEvent(evt))

}
