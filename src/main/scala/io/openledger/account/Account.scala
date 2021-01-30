package io.openledger.account

import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import io.openledger.account.AccountMode.{AccountMode, CREDIT, DEBIT}
import io.openledger.account.states.{AccountState, CreditAccount, DebitAccount}
import io.openledger.{JsonSerializable, LedgerError}

import java.util.UUID

object Account {

  def apply(accountId: UUID, mode: AccountMode): Behavior[AccountCommand] =
    EventSourcedBehavior[AccountCommand, AccountEvent, AccountState](
      persistenceId = PersistenceId.ofUniqueId(accountId.toString),
      emptyState = mode match {
        case CREDIT => CreditAccount(BigDecimal(0), BigDecimal(0), BigDecimal(0))
        case DEBIT => DebitAccount(BigDecimal(0), BigDecimal(0), BigDecimal(0))
      },
      commandHandler = (state, cmd) => state.handleCommand(cmd),
      eventHandler = (state, evt) => state.handleEvent(evt))

  sealed trait AccountCommand extends JsonSerializable

  sealed trait AccountingStatus extends AccountCommand

  sealed trait AccountEvent extends JsonSerializable

  final case class Debit(amountToDebit: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  final case class DebitAdjust(amountToDebit: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  final case class Credit(amountToCredit: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  final case class CreditAdjust(amountToCredit: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  final case class Hold(amountToHold: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  final case class Capture(amountToCapture: BigDecimal, amountToRelease: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  final case class Release(amountToRelease: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  final case class AccountingSuccessful(availableBalance: BigDecimal, currentBalance: BigDecimal, authorizedBalance: BigDecimal) extends AccountingStatus

  final case class AccountingFailed(code: LedgerError.Value) extends AccountingStatus

  final case class Debited(newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal) extends AccountEvent

  final case class Credited(newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal) extends AccountEvent

  final case class Authorized(newAvailableBalance: BigDecimal, newAuthorizedBalance: BigDecimal) extends AccountEvent

  final case class Captured(newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal, newAuthorizedBalance: BigDecimal) extends AccountEvent

  final case class Released(newAvailableBalance: BigDecimal, newAuthorizedBalance: BigDecimal) extends AccountEvent

  final case object Overdrawn extends AccountEvent

  final case object Overpaid extends AccountEvent

}
