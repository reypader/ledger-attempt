package io.openledger.domain.entry.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.LedgerError
import io.openledger.domain.entry.Entry
import io.openledger.domain.entry.Entry._
import io.openledger.events.{Done, EntryEvent}

case class Failed(entryCode: String, entryId: String, code: String) extends EntryState {
  override def handleEvent(event: EntryEvent)(implicit
      context: ActorContext[EntryCommand]
  ): PartialFunction[EntryEvent, EntryState] = { case Done(_) => this }

  override def handleCommand(command: Entry.EntryCommand)(implicit
      context: ActorContext[EntryCommand],
      accountMessenger: AccountMessenger,
      resultMessenger: ResultMessenger
  ): PartialFunction[EntryCommand, Effect[EntryEvent, EntryState]] = PartialFunction.empty

  override def proceed()(implicit
      context: ActorContext[EntryCommand],
      accountMessenger: AccountMessenger,
      resultMessenger: ResultMessenger
  ): Unit = {
    context.log.info(s"Announcing result on Failed")
    resultMessenger(EntryFailed(entryId, LedgerError.withName(code)))
  }
}
