package io.openledger.domain.account

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import io.openledger.DateUtils.TimeGen
import io.openledger.domain.account.AccountMode.AccountMode
import io.openledger.domain.account.states.{AccountState, Ready}
import io.openledger.events.AccountEvent
import io.openledger.{LedgerError, LedgerSerializable}

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

  sealed trait AccountCommand extends LedgerSerializable

  sealed trait AccountingCommand extends AccountCommand {
    def transactionId: String
  }


  sealed trait AccountingStatus extends LedgerSerializable {
    def accountId: String
  }

  final case class Open(mode: AccountMode) extends AccountCommand

  final case class Debit(transactionId: String, entryCode: String, amountToDebit: BigDecimal) extends AccountingCommand

  final case class DebitAdjust(transactionId: String, entryCode: String, amountToDebit: BigDecimal) extends AccountingCommand

  final case class Credit(transactionId: String, entryCode: String, amountToCredit: BigDecimal) extends AccountingCommand

  final case class CreditAdjust(transactionId: String, entryCode: String, amountToCredit: BigDecimal) extends AccountingCommand

  final case class DebitHold(transactionId: String, entryCode: String, amountToHold: BigDecimal) extends AccountingCommand

  final case class Post(transactionId: String, entryCode: String, amountToCapture: BigDecimal, amountToRelease: BigDecimal, postingTimestamp: OffsetDateTime) extends AccountingCommand

  final case class Release(transactionId: String, entryCode: String, amountToRelease: BigDecimal) extends AccountingCommand

  final case class Get(replyTo: ActorRef[AccountState]) extends AccountCommand

  final case class AccountingSuccessful(commandHash: Int, accountId: String, availableBalance: BigDecimal, currentBalance: BigDecimal, authorizedBalance: BigDecimal, timestamp: OffsetDateTime) extends AccountingStatus

  final case class AccountingFailed(commandHash: Int, accountId: String, code: LedgerError.Value) extends AccountingStatus


}
