package io.openledger.domain.transaction

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import io.openledger.domain.account.Account.AccountingCommand
import io.openledger.domain.transaction.states.{Ready, TransactionState}
import io.openledger.events._
import io.openledger.{LedgerError, LedgerSerializable, ResultingBalance}

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

  sealed trait TransactionResult extends LedgerSerializable

  sealed trait TransactionCommand extends LedgerSerializable

  sealed trait TxnAck

  final case class Begin(entryCode: String, accountToDebit: String, accountToCredit: String, amount: BigDecimal, replyTo: ActorRef[TxnAck], authOnly: Boolean = false) extends TransactionCommand

  final case class Reverse(replyTo: ActorRef[TxnAck]) extends TransactionCommand

  final case class Capture(captureAmount: BigDecimal, replyTo: ActorRef[TxnAck]) extends TransactionCommand

  final case class Resume(replyTo: ActorRef[TxnAck]) extends TransactionCommand

  final case class AcceptAccounting(commandHash: Int, accountId: String, resultingBalance: ResultingBalance, timestamp: OffsetDateTime) extends TransactionCommand

  final case class RejectAccounting(commandHash: Int, accountId: String, code: LedgerError.Value) extends TransactionCommand

  final case class TransactionSuccessful(debitedAccountResultingBalance: ResultingBalance, creditedAccountResultingBalance: ResultingBalance) extends TransactionResult

  final case class TransactionFailed(code: LedgerError.Value) extends TransactionResult

  final case class TransactionReversed() extends TransactionResult

  final case class TransactionPending() extends TransactionResult

  final case class CaptureRejected(code: LedgerError.Value) extends TransactionResult

  final case object Ack extends TxnAck

}
