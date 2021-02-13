package io.openledger.domain.entry.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.ResultingBalance
import io.openledger.domain.entry.Entry
import io.openledger.domain.entry.Entry._
import io.openledger.events.EntryEvent

case class Reversed(
    entryCode: String,
    entryId: String,
    accountToDebit: String,
    accountToCredit: String,
    debitReversedResultingBalance: ResultingBalance,
    creditReversedResultingBalance: Option[ResultingBalance]
) extends PairedEntry {
  override def handleEvent(event: EntryEvent)(implicit
      context: ActorContext[EntryCommand]
  ): PartialFunction[EntryEvent, EntryState] = PartialFunction.empty

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
    context.log.info(s"Announcing result on Reversed")
    resultMessenger(EntryReversed(entryId, debitReversedResultingBalance, creditReversedResultingBalance))
  }
}
