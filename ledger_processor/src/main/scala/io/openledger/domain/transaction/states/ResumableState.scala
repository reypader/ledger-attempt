package io.openledger.domain.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.domain.transaction.Transaction
import io.openledger.domain.transaction.Transaction.{AccountMessenger, Ack, ResultMessenger, Resume, Reverse}
import io.openledger.events.{Resumed, ReversalRequested, TransactionEvent}

case class ResumablePosting(actualState:Posting) extends ResumableState(actualState) {
  override def handleEvent(event: TransactionEvent)(implicit context: ActorContext[Transaction.TransactionCommand]): PartialFunction[TransactionEvent, TransactionState] =
    super.handleEvent(event).orElse {
      case ReversalRequested() => copy(actualState = actualState.copy(reversalPending = true))
    }
  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[Transaction.TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): PartialFunction[Transaction.TransactionCommand, Effect[TransactionEvent, TransactionState]] =
    super.handleCommand(command).orElse {
      case Reverse(replyTo) => Effect.persist(ReversalRequested())
        .thenRun { next: TransactionState =>
          replyTo ! Ack
        }
    }
}
case class ResumableRollingBackCredit(actualState:RollingBackCredit) extends ResumableState(actualState)
case class ResumableRollingBackDebit(actualState:RollingBackDebit) extends ResumableState(actualState)

abstract class ResumableState(actualState: TransactionState) extends TransactionState {
  override def handleEvent(event: TransactionEvent)(implicit context: ActorContext[Transaction.TransactionCommand]): PartialFunction[TransactionEvent, TransactionState] = {
    case Resumed() => actualState
  }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[Transaction.TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): PartialFunction[Transaction.TransactionCommand, Effect[TransactionEvent, TransactionState]] = {
    case Resume(replyTo) =>
      Effect.persist(Resumed())
        .thenRun { next: TransactionState =>
          next.proceed()
          replyTo ! Ack
        }
  }

  override def proceed()(implicit context: ActorContext[Transaction.TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    context.log.error(s"ALERT: transaction entered ResumableState. Manual adjustment may be needed: $actualState")
  }
}
