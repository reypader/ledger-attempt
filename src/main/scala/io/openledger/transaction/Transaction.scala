package io.openledger.transaction

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import io.openledger.account.Account.AccountingCommand
import io.openledger.transaction.states.{Ready, TransactionState}
import io.openledger.{JsonSerializable, LedgerError}

import java.time.OffsetDateTime

object Transaction {

  type AccountMessenger = (String, AccountingCommand) => Unit
  type ResultMessenger = TransactionResult => Unit

  def apply(transactionId: String)(implicit accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Behavior[TransactionCommand] =
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

  final case class Begin(accountToDebit: String, accountToCredit: String, amount: BigDecimal, authOnly: Boolean = false) extends TransactionCommand

  final case class AcceptAccounting(accountId: String, resultingBalance: ResultingBalance, timestamp: OffsetDateTime) extends TransactionCommand

  final case class RejectAccounting(accountId: String, code: LedgerError.Value) extends TransactionCommand

  final case class Reverse() extends TransactionCommand

  final case class Capture(captureAmount: BigDecimal) extends TransactionCommand

  final case class Started(accountToDebit: String, accountToCredit: String, amount: BigDecimal, authOnly: Boolean) extends TransactionEvent

  final case class DebitHoldSucceeded(debitedAccountResultingBalance: ResultingBalance, timestamp: OffsetDateTime) extends TransactionEvent

  final case class DebitHoldFailed(code: LedgerError.Value) extends TransactionEvent

  final case class DebitPostSucceeded(debitedAccountResultingBalance: ResultingBalance) extends TransactionEvent

  final case class DebitPostFailed(code: LedgerError.Value) extends TransactionEvent

  final case class CreditSucceeded(creditedAccountResultingBalance: ResultingBalance) extends TransactionEvent

  final case class CreditFailed(code: LedgerError.Value) extends TransactionEvent

  final case class CreditAdjustmentDone(debitedAccountResultingBalance: ResultingBalance) extends TransactionEvent

  final case class DebitAdjustmentDone(creditedAccountResultingBalance: ResultingBalance) extends TransactionEvent

  final case class ReversalRequested() extends TransactionEvent
  final case class CaptureRequested(captureAmount:BigDecimal) extends TransactionEvent

  final case class ResultingBalance(availableBalance: BigDecimal, currentBalance: BigDecimal)

  final case class TransactionSuccessful(debitedAccountResultingBalance: ResultingBalance, creditedAccountResultingBalance: ResultingBalance) extends TransactionResult

  final case class TransactionFailed(code: LedgerError.Value) extends TransactionResult

  final case class TransactionReversed() extends TransactionResult

  final case class TransactionPending() extends TransactionResult


}
