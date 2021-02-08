package io.openledger.domain.transaction

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import io.openledger.AccountingMode.AccountMode
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
        commandHandler = (state, cmd) => {
          actorContext.log.info(s"Handling command $cmd")
          state.handleCommand(cmd).orElse[TransactionCommand, Effect[TransactionEvent, TransactionState]] {
            case Get(replyTo) => Effect.none.thenReply(replyTo)(s => s)
            case ackable: Ackable =>
              actorContext.log.warn(s"Unhandled Ackable $ackable. Rejecting...")
              Effect.none
                .thenRun { _ =>
                  ackable.replyTo ! Nack
                  resultMessenger(CommandRejected(transactionId, LedgerError.UNSUPPORTED_TRANSACTION_OPERATION_ON_CURRENT_STATE))
                }
            case _ =>
              actorContext.log.warn(s"Unhandled command $cmd")
              Effect.none
          }(cmd)
        },
        eventHandler = (state, evt) => {
          actorContext.log.info(s"Handling event $evt")
          state.handleEvent(evt).orElse[TransactionEvent, TransactionState] {
            case _ =>
              actorContext.log.warn(s"Unhandled event $evt")
              state
          }(evt)
        })
    }

  sealed trait TransactionResult extends LedgerSerializable {
    def status: String

    def code: String

    def transactionId: String
  }

  sealed trait Ackable {
    def replyTo: ActorRef[TxnAck]
  }

  sealed trait TransactionCommand extends LedgerSerializable

  sealed trait TxnAck

  final case class Get(replyTo: ActorRef[TransactionState]) extends TransactionCommand

  final case class Adjust(entryCode: String, accountToAdjust: String, amount: BigDecimal, mode: AccountMode, replyTo: ActorRef[TxnAck]) extends TransactionCommand with Ackable

  final case class Begin(entryCode: String, accountToDebit: String, accountToCredit: String, amount: BigDecimal, replyTo: ActorRef[TxnAck], authOnly: Boolean = false) extends TransactionCommand with Ackable

  final case class Reverse(replyTo: ActorRef[TxnAck]) extends TransactionCommand with Ackable

  final case class Capture(captureAmount: BigDecimal, replyTo: ActorRef[TxnAck]) extends TransactionCommand with Ackable

  final case class Resume(replyTo: ActorRef[TxnAck]) extends TransactionCommand with Ackable

  final case class AcceptAccounting(commandHash: Int, accountId: String, resultingBalance: ResultingBalance, timestamp: OffsetDateTime) extends TransactionCommand

  final case class RejectAccounting(commandHash: Int, accountId: String, code: LedgerError.Value) extends TransactionCommand

  final case class AdjustmentSuccessful(transactionId: String, resultingBalance: ResultingBalance) extends TransactionResult {
    override def status = "SUCCESS"

    override def code = "SUCCESS"
  }

  final case class TransactionSuccessful(transactionId: String, debitedAccountResultingBalance: ResultingBalance, creditedAccountResultingBalance: ResultingBalance) extends TransactionResult {
    override def status = "SUCCESS"

    override def code = "SUCCESS"
  }

  final case class TransactionFailed(transactionId: String, errorCode: LedgerError.Value) extends TransactionResult {
    override def status = "FAILED"

    override def code = errorCode.toString
  }

  final case class CommandRejected(transactionId: String, errorCode: LedgerError.Value) extends TransactionResult {
    override def status = "REJECTED"

    override def code = errorCode.toString
  }

  final case class TransactionReversed(transactionId: String, debitedAccountResultingBalance: ResultingBalance, creditedAccountResultingBalance: Option[ResultingBalance]) extends TransactionResult {
    override def status = "REVERSED"

    override def code = "REVERSED"
  }

  final case class TransactionPending(transactionId: String, debitedAccountResultingBalance: ResultingBalance) extends TransactionResult {
    override def status = "PENDING"

    override def code = "PENDING"
  }

  final case class CaptureRejected(transactionId: String, errorCode: LedgerError.Value) extends TransactionResult {
    override def status = "REJECTED"

    override def code = errorCode.toString
  }

  final case object Ack extends TxnAck

  final case object Nack extends TxnAck

}
