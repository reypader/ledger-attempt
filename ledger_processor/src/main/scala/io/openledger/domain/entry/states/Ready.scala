package io.openledger.domain.entry.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.domain.entry.Entry
import io.openledger.domain.entry.Entry._
import io.openledger.events._

case class Ready(entryId: String) extends EntryState {
  override def handleEvent(
      event: EntryEvent
  )(implicit context: ActorContext[EntryCommand]): PartialFunction[EntryEvent, EntryState] = {
    case Started(entryCode, accountToDebit, accountToCredit, amount, authOnly) =>
      Authorizing(entryCode, entryId, accountToDebit, accountToCredit, amount, authOnly, reversalPending = false)
    case AdjustRequested(entryCode, accountToAdjust, amount, mode) =>
      Adjusting(entryCode, entryId, accountToAdjust, amount, mode)
  }

  override def handleCommand(command: Entry.EntryCommand)(implicit
      context: ActorContext[EntryCommand],
      accountMessenger: AccountMessenger,
      resultMessenger: ResultMessenger
  ): PartialFunction[EntryCommand, Effect[EntryEvent, EntryState]] = {
    case Begin(entryCode, accountToDebit, accountToCredit, amount, replyTo, authOnly) =>
      Effect
        .persist(Started(entryCode, accountToDebit, accountToCredit, amount, authOnly))
        .thenRun { next: EntryState =>
          next.proceed()
          replyTo ! Ack
        }
    case Adjust(entryCode, accountToAdjust, amount, mode, replyTo) =>
      Effect
        .persist(AdjustRequested(entryCode, accountToAdjust, amount, mode))
        .thenRun { next: EntryState =>
          next.proceed()
          replyTo ! Ack
        }
  }

  override def proceed()(implicit
      context: ActorContext[EntryCommand],
      accountMessenger: AccountMessenger,
      resultMessenger: ResultMessenger
  ): Unit = {
    context.log.info(s"Doing nothing on Ready")
  }
}
