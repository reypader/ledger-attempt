package io.openledger.account

import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import io.openledger.account.AccountMode.{AccountMode, CREDIT, DEBIT}
import io.openledger.account.states.{AccountState, CreditAccount, DebitAccount}
import io.openledger.{JsonSerializable, LedgerError}

import java.util.UUID

object Account {

  def apply(accountId: String, mode: AccountMode): Behavior[AccountCommand] =
    EventSourcedBehavior[AccountCommand, AccountEvent, AccountState](
      persistenceId = PersistenceId.ofUniqueId(accountId),
      emptyState = mode match {
        case CREDIT => CreditAccount(BigDecimal(0), BigDecimal(0), BigDecimal(0))
        case DEBIT => DebitAccount(BigDecimal(0), BigDecimal(0), BigDecimal(0))
      },
      commandHandler = (state, cmd) => state.handleCommand(cmd),
      eventHandler = (state, evt) => state.handleEvent(evt))

  sealed trait AccountingStatus extends JsonSerializable{
    def entryId: String
  }

  sealed trait AccountCommand extends JsonSerializable {
    def entryId: String
  }

  sealed trait AccountEvent extends JsonSerializable{
    def entryId: String
  }

  final case class Debit(entryId: String, amountToDebit: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  final case class DebitAdjust(entryId: String, amountToDebit: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  final case class Credit(entryId: String, amountToCredit: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  final case class CreditAdjust(entryId: String, amountToCredit: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  final case class Hold(entryId: String, amountToHold: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  final case class Capture(entryId: String, amountToCapture: BigDecimal, amountToRelease: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  final case class Release(entryId: String, amountToRelease: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  final case class AccountingSuccessful(entryId: String, availableBalance: BigDecimal, currentBalance: BigDecimal, authorizedBalance: BigDecimal) extends AccountingStatus

  final case class AccountingFailed(entryId: String, code: LedgerError.Value) extends AccountingStatus

  final case class Debited(entryId: String, newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal) extends AccountEvent

  final case class Credited(entryId: String, newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal) extends AccountEvent

  final case class Authorized(entryId: String, newAvailableBalance: BigDecimal, newAuthorizedBalance: BigDecimal) extends AccountEvent

  final case class Captured(entryId: String, newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal, newAuthorizedBalance: BigDecimal) extends AccountEvent

  final case class Released(entryId: String, newAvailableBalance: BigDecimal, newAuthorizedBalance: BigDecimal) extends AccountEvent

  final case class Overdrawn(entryId: String) extends AccountEvent

  final case class Overpaid(entryId: String) extends AccountEvent

}
