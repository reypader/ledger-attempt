package io.openledger.domain.entry.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.DateUtils
import io.openledger.domain.entry.Entry
import io.openledger.domain.entry.Entry.{AccountMessenger, Ack, ResultMessenger, Resume, Reverse}
import io.openledger.events.{EntryEvent, Resumed, ReversalRequested}

case class ResumableCapturing(actualState: Capturing) extends ResumableState(actualState) {
  override def handleEvent(
      event: EntryEvent
  )(implicit context: ActorContext[Entry.EntryCommand]): PartialFunction[EntryEvent, EntryState] =
    super.handleEvent(event).orElse { case ReversalRequested(_) =>
      copy(actualState = actualState.copy(reversalPending = true))
    }
  override def handleCommand(command: Entry.EntryCommand)(implicit
      context: ActorContext[Entry.EntryCommand],
      accountMessenger: AccountMessenger,
      resultMessenger: ResultMessenger
  ): PartialFunction[Entry.EntryCommand, Effect[EntryEvent, EntryState]] =
    super.handleCommand(command).orElse { case Reverse(replyTo) =>
      Effect
        .persist(ReversalRequested(DateUtils.now()))
        .thenRun { next: EntryState =>
          replyTo ! Ack
        }
    }
}
case class ResumableRollingBackCredit(actualState: RollingBackCredit) extends ResumableState(actualState)
case class ResumableRollingBackDebit(actualState: RollingBackDebit) extends ResumableState(actualState)

abstract class ResumableState(actualState: EntryState) extends EntryState {
  override def handleEvent(
      event: EntryEvent
  )(implicit context: ActorContext[Entry.EntryCommand]): PartialFunction[EntryEvent, EntryState] = { case Resumed() =>
    actualState
  }

  override def handleCommand(command: Entry.EntryCommand)(implicit
      context: ActorContext[Entry.EntryCommand],
      accountMessenger: AccountMessenger,
      resultMessenger: ResultMessenger
  ): PartialFunction[Entry.EntryCommand, Effect[EntryEvent, EntryState]] = { case Resume(replyTo) =>
    Effect
      .persist(Resumed())
      .thenRun { next: EntryState =>
        next.proceed()
        replyTo ! Ack
      }
  }

  override def proceed()(implicit
      context: ActorContext[Entry.EntryCommand],
      accountMessenger: AccountMessenger,
      resultMessenger: ResultMessenger
  ): Unit = {
    context.log.error(s"ALERT: entry entered ResumableState. Manual adjustment may be needed: $actualState")
  }
}
