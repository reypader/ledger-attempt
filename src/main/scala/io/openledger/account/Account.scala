package io.openledger.account

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import io.openledger.DateUtils.TimeGen
import io.openledger.account.AccountMode.AccountMode
import io.openledger.account.states.{AccountState, Ready}
import io.openledger.{JsonSerializable, LedgerError}

import java.time.OffsetDateTime

object Account {

  type TransactionMessenger = (String, AccountingStatus) => Unit

  def apply(accountId: String)(implicit messenger: TransactionMessenger, timeGen: TimeGen): Behavior[AccountCommand] =
    Behaviors.setup { implicit actorContext: ActorContext[AccountCommand] =>
      EventSourcedBehavior[AccountCommand, AccountEvent, AccountState](
        persistenceId = PersistenceId.ofUniqueId(accountId),
        emptyState = Ready(accountId),
        commandHandler = (state, cmd) => cmd match {
          case Get(replyTo) => Effect.none.thenReply(replyTo)(state => state)
          case _ => state.handleCommand(cmd)
        },
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
    def accountId: String
  }

  final case class Open(mode: AccountMode) extends AccountCommand

  final case class Debit(transactionId: String, amountToDebit: BigDecimal) extends AccountingCommand

  final case class DebitAdjust(transactionId: String, amountToDebit: BigDecimal) extends AccountingCommand

  final case class Credit(transactionId: String, amountToCredit: BigDecimal) extends AccountingCommand

  final case class CreditAdjust(transactionId: String, amountToCredit: BigDecimal) extends AccountingCommand

  final case class DebitHold(transactionId: String, amountToHold: BigDecimal) extends AccountingCommand

  final case class Post(transactionId: String, amountToCapture: BigDecimal, amountToRelease: BigDecimal, postingTimestamp: OffsetDateTime) extends AccountingCommand

  final case class Release(transactionId: String, amountToRelease: BigDecimal) extends AccountingCommand

  final case class Get(replyTo: ActorRef[AccountState]) extends AccountCommand

  final case class AccountingSuccessful(accountId: String, availableBalance: BigDecimal, currentBalance: BigDecimal, authorizedBalance: BigDecimal, timestamp: OffsetDateTime) extends AccountingStatus

  final case class AccountingFailed(accountId: String, code: LedgerError.Value) extends AccountingStatus

  final case class DebitAccountOpened(timestamp: OffsetDateTime) extends AccountEvent

  final case class CreditAccountOpened(timestamp: OffsetDateTime) extends AccountEvent

  final case class Debited(transactionId: String, newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal, timestamp: OffsetDateTime) extends AccountingEvent

  final case class Credited(transactionId: String, newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal, timestamp: OffsetDateTime) extends AccountingEvent

  final case class DebitAuthorized(transactionId: String, newAvailableBalance: BigDecimal, newAuthorizedBalance: BigDecimal, timestamp: OffsetDateTime) extends AccountingEvent

  final case class DebitPosted(transactionId: String, newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal, newAuthorizedBalance: BigDecimal, postingTimestamp: OffsetDateTime, timestamp: OffsetDateTime) extends AccountingEvent

  final case class Released(transactionId: String, newAvailableBalance: BigDecimal, newAuthorizedBalance: BigDecimal, timestamp: OffsetDateTime) extends AccountingEvent

  final case class Overdrawn(transactionId: String, timestamp: OffsetDateTime) extends AccountingEvent

  final case class Overpaid(transactionId: String, timestamp: OffsetDateTime) extends AccountingEvent

}
