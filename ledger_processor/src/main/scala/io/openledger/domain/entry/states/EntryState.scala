package io.openledger.domain.entry.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.LedgerSerializable
import io.openledger.domain.entry.Entry.{AccountMessenger, ResultMessenger, EntryCommand}
import io.openledger.events._

trait EntryState extends LedgerSerializable {
  def handleEvent(event: EntryEvent)(implicit
      context: ActorContext[EntryCommand]
  ): PartialFunction[EntryEvent, EntryState]

  def proceed()(implicit
      context: ActorContext[EntryCommand],
      accountMessenger: AccountMessenger,
      resultMessenger: ResultMessenger
  ): Unit

  def handleCommand(command: EntryCommand)(implicit
      context: ActorContext[EntryCommand],
      accountMessenger: AccountMessenger,
      resultMessenger: ResultMessenger
  ): PartialFunction[EntryCommand, Effect[EntryEvent, EntryState]]
}
