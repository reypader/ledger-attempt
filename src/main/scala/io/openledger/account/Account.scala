package io.openledger.account

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import io.openledger.account.AccountMode.AccountMode
import io.openledger.account.states.{AccountState, Ready}
import io.openledger.{JsonSerializable, LedgerError}

object Account {

  type TransactionMessenger = (String, AccountingStatus) => Unit

  def apply(accountId: String)(implicit messenger: TransactionMessenger): Behavior[AccountCommand] =
    Behaviors.setup { implicit actorContext: ActorContext[AccountCommand] =>
      EventSourcedBehavior[AccountCommand, AccountEvent, AccountState](
        persistenceId = PersistenceId.ofUniqueId(accountId),
        emptyState = Ready(),
        commandHandler = (state, cmd) => state.handleCommand(cmd),
        eventHandler = (state, evt) => state.handleEvent(evt))
    }

  sealed trait AccountCommand extends JsonSerializable

  sealed trait AccountingCommand extends AccountCommand {
    def transactionId: String
  }

  sealed trait AccountEvent extends JsonSerializable

  sealed trait AccountingEvent extends AccountEvent {
    def transactionId: String
  }

  sealed trait AccountingStatus extends JsonSerializable {
    def transactionId: String
  }

  final case class Open(mode: AccountMode) extends AccountCommand

  final case class Debit(transactionId: String, amountToDebit: BigDecimal) extends AccountingCommand

  final case class DebitAdjust(transactionId: String, amountToDebit: BigDecimal) extends AccountingCommand

  final case class Credit(transactionId: String, amountToCredit: BigDecimal) extends AccountingCommand

  final case class CreditAdjust(transactionId: String, amountToCredit: BigDecimal) extends AccountingCommand

  final case class Hold(transactionId: String, amountToHold: BigDecimal) extends AccountingCommand

  final case class Capture(transactionId: String, amountToCapture: BigDecimal, amountToRelease: BigDecimal) extends AccountingCommand

  final case class Release(transactionId: String, amountToRelease: BigDecimal) extends AccountingCommand

  final case class AccountingSuccessful(transactionId: String, availableBalance: BigDecimal, currentBalance: BigDecimal, authorizedBalance: BigDecimal) extends AccountingStatus

  final case class AccountingFailed(transactionId: String, code: LedgerError.Value) extends AccountingStatus

  final case class DebitAccountOpened() extends AccountEvent

  final case class CreditAccountOpened() extends AccountEvent

  final case class Debited(transactionId: String, newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal) extends AccountingEvent

  final case class Credited(transactionId: String, newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal) extends AccountingEvent

  final case class Authorized(transactionId: String, newAvailableBalance: BigDecimal, newAuthorizedBalance: BigDecimal) extends AccountingEvent

  final case class Captured(transactionId: String, newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal, newAuthorizedBalance: BigDecimal) extends AccountingEvent

  final case class Released(transactionId: String, newAvailableBalance: BigDecimal, newAuthorizedBalance: BigDecimal) extends AccountingEvent

  final case class Overdrawn(transactionId: String) extends AccountingEvent

  final case class Overpaid(transactionId: String) extends AccountingEvent

}
