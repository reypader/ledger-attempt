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
    def transactionId: String
  }

  sealed trait AccountCommand extends JsonSerializable {
    def transactionId: String
  }

  sealed trait AccountEvent extends JsonSerializable{
    def transactionId: String
  }

  final case class Debit(transactionId: String, amountToDebit: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  final case class DebitAdjust(transactionId: String, amountToDebit: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  final case class Credit(transactionId: String, amountToCredit: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  final case class CreditAdjust(transactionId: String, amountToCredit: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  final case class Hold(transactionId: String, amountToHold: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  final case class Capture(transactionId: String, amountToCapture: BigDecimal, amountToRelease: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  final case class Release(transactionId: String, amountToRelease: BigDecimal, replyTo: ActorRef[AccountingStatus]) extends AccountCommand

  final case class AccountingSuccessful(transactionId: String, availableBalance: BigDecimal, currentBalance: BigDecimal, authorizedBalance: BigDecimal) extends AccountingStatus

  final case class AccountingFailed(transactionId: String, code: LedgerError.Value) extends AccountingStatus

  final case class Debited(transactionId: String, newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal) extends AccountEvent

  final case class Credited(transactionId: String, newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal) extends AccountEvent

  final case class Authorized(transactionId: String, newAvailableBalance: BigDecimal, newAuthorizedBalance: BigDecimal) extends AccountEvent

  final case class Captured(transactionId: String, newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal, newAuthorizedBalance: BigDecimal) extends AccountEvent

  final case class Released(transactionId: String, newAvailableBalance: BigDecimal, newAuthorizedBalance: BigDecimal) extends AccountEvent

  final case class Overdrawn(transactionId: String) extends AccountEvent

  final case class Overpaid(transactionId: String) extends AccountEvent

}
