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

  final case class Debit(amountToDebit: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  final case class DebitAdjust(amountToDebit: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  final case class Credit(amountToCredit: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  final case class CreditAdjust(amountToCredit: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  final case class Hold(amountToHold: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  final case class Capture(amountToCapture: BigDecimal, amountToRelease: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  final case class Release(amountToRelease: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  sealed trait AccountingStatus extends AccountCommand

  final case class AccountingSuccessful(availableBalance: BigDecimal, currentBalance: BigDecimal, authorizedBalance: BigDecimal) extends AccountingStatus

  final case class AccountingFailed(code: LedgerError.Value) extends AccountingStatus


  sealed trait AccountEvent extends JsonSerializable

  final case class Debited(newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal) extends AccountEvent

  final case class Credited(newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal) extends AccountEvent

  final case class Authorized(newAvailableBalance: BigDecimal, newAuthorizedBalance: BigDecimal) extends AccountEvent

  final case class Captured(newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal, newAuthorizedBalance: BigDecimal) extends AccountEvent

  final case class Released(newAvailableBalance: BigDecimal, newAuthorizedBalance: BigDecimal) extends AccountEvent

  final case class Overdraft(newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal, newAuthorizedBalance: BigDecimal) extends AccountEvent

  final case class Overpayment(newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal, newAuthorizedBalance: BigDecimal) extends AccountEvent

  def apply(accountId: UUID, mode: AccountMode): Behavior[AccountCommand] =
    EventSourcedBehavior[AccountCommand, AccountEvent, AccountState](
      persistenceId = PersistenceId.ofUniqueId(accountId.toString),
      emptyState = CreditAccount(BigDecimal(0), BigDecimal(0), BigDecimal(0)),
      commandHandler = (state, cmd) => state.handleCommand(cmd),
      eventHandler = (state, evt) => state.handleEvent(evt))

}
