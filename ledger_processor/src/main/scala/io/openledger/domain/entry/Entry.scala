package io.openledger.domain.entry

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import io.openledger.AccountingMode.AccountMode
import io.openledger.domain.account.Account.AccountingCommand
import io.openledger.domain.entry.states.{Ready, EntryState}
import io.openledger.events._
import io.openledger.{LedgerError, LedgerSerializable, ResultingBalance}

import java.time.OffsetDateTime

object Entry {
  type AccountMessenger = (String, AccountingCommand) => Unit
  type ResultMessenger = EntryResult => Unit
  val EntryTypeKey: EntityTypeKey[EntryCommand] = EntityTypeKey[Entry.EntryCommand]("Entry")

  def apply(
      entryId: String
  )(implicit accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Behavior[EntryCommand] =
    Behaviors.setup { implicit actorContext: ActorContext[EntryCommand] =>
      EventSourcedBehavior[EntryCommand, EntryEvent, EntryState](
        persistenceId = PersistenceId.of(EntryTypeKey.name, entryId),
        emptyState = Ready(entryId),
        commandHandler = (state, cmd) => {
          actorContext.log.info(s"Handling command $cmd")
          state
            .handleCommand(cmd)
            .orElse[EntryCommand, Effect[EntryEvent, EntryState]] {
              case Get(replyTo) => Effect.none.thenReply(replyTo)(s => s)
              case ackable: Ackable =>
                actorContext.log.warn(s"Unhandled Ackable $ackable. Rejecting...")
                Effect.none
                  .thenRun { _ =>
                    resultMessenger(CommandRejected(entryId, LedgerError.UNSUPPORTED_ENTRY_OPERATION_ON_CURRENT_STATE))
                    ackable.replyTo ! Nack
                  }
              case _ =>
                actorContext.log.warn(s"Unhandled command $cmd")
                Effect.none
            }(cmd)
        },
        eventHandler = (state, evt) => {
          actorContext.log.info(s"Handling event $evt")
          state
            .handleEvent(evt)
            .orElse[EntryEvent, EntryState] { case _ =>
              actorContext.log.warn(s"Unhandled event $evt")
              state
            }(evt)
        }
      ).withTagger(_ => Set(EntryEvent.tagDistribution.assignTag(entryId)))
    }

  sealed trait EntryResult extends LedgerSerializable {
    def status: String

    def code: String

    def entryId: String
  }

  sealed trait Ackable extends LedgerSerializable {
    def replyTo: ActorRef[TxnAck]
  }

  sealed trait EntryCommand extends LedgerSerializable

  sealed trait TxnAck

  final case class Get(replyTo: ActorRef[EntryState]) extends EntryCommand

  final case class Adjust(
      entryCode: String,
      accountToAdjust: String,
      amount: BigDecimal,
      mode: AccountMode,
      replyTo: ActorRef[TxnAck]
  ) extends EntryCommand
      with Ackable

  final case class Begin(
      entryCode: String,
      accountToDebit: String,
      accountToCredit: String,
      amount: BigDecimal,
      replyTo: ActorRef[TxnAck],
      authOnly: Boolean = false
  ) extends EntryCommand
      with Ackable

  final case class Reverse(replyTo: ActorRef[TxnAck]) extends EntryCommand with Ackable

  final case class Capture(captureAmount: BigDecimal, replyTo: ActorRef[TxnAck]) extends EntryCommand with Ackable

  final case class Resume(replyTo: ActorRef[TxnAck]) extends EntryCommand with Ackable

  final case class AcceptAccounting(
      commandHash: Int,
      accountId: String,
      resultingBalance: ResultingBalance,
      timestamp: OffsetDateTime
  ) extends EntryCommand

  final case class RejectAccounting(commandHash: Int, accountId: String, code: LedgerError.Value) extends EntryCommand

  final case class AdjustmentSuccessful(entryId: String, mode: AccountMode, resultingBalance: ResultingBalance)
      extends EntryResult {
    override def status = "SUCCESS"

    override def code = "SUCCESS"
  }

  final case class EntrySuccessful(
      entryId: String,
      debitedAccountResultingBalance: ResultingBalance,
      creditedAccountResultingBalance: ResultingBalance
  ) extends EntryResult {
    override def status = "SUCCESS"

    override def code = "SUCCESS"
  }

  final case class EntryFailed(entryId: String, errorCode: LedgerError.Value) extends EntryResult {
    override def status = "FAILED"

    override def code = errorCode.toString
  }

  final case class CommandRejected(entryId: String, errorCode: LedgerError.Value) extends EntryResult {
    override def status = "REJECTED"

    override def code = errorCode.toString
  }

  final case class EntryReversed(
      entryId: String,
      debitedAccountResultingBalance: ResultingBalance,
      creditedAccountResultingBalance: Option[ResultingBalance]
  ) extends EntryResult {
    override def status = "REVERSED"

    override def code = "REVERSED"
  }

  final case class EntryPending(entryId: String, debitedAccountResultingBalance: ResultingBalance) extends EntryResult {
    override def status = "PENDING"

    override def code = "PENDING"
  }

  final case class CaptureRejected(entryId: String, errorCode: LedgerError.Value) extends EntryResult {
    override def status = "REJECTED"

    override def code = errorCode.toString
  }

  final case object Ack extends TxnAck

  final case object Nack extends TxnAck

}
