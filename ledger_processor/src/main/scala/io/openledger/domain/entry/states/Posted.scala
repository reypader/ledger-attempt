package io.openledger.domain.entry.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.{DateUtils, ResultingBalance}
import io.openledger.domain.entry.Entry
import io.openledger.domain.entry.Entry._
import io.openledger.events._

case class Posted(
    entryCode: String,
    entryId: String,
    accountToDebit: String,
    accountToCredit: String,
    amountCaptured: BigDecimal,
    debitedAccountResultingBalance: ResultingBalance,
    creditedAccountResultingBalance: ResultingBalance
) extends PairedEntry {
  override def handleEvent(event: EntryEvent)(implicit
      context: ActorContext[EntryCommand]
  ): PartialFunction[EntryEvent, EntryState] = {
    case ReversalRequested(_) =>
      RollingBackCredit(entryCode, entryId, accountToDebit, accountToCredit, amountCaptured, Some(amountCaptured), None)
    case Done(_) => this
  }

  override def handleCommand(command: Entry.EntryCommand)(implicit
      context: ActorContext[EntryCommand],
      accountMessenger: AccountMessenger,
      resultMessenger: ResultMessenger
  ): PartialFunction[EntryCommand, Effect[EntryEvent, EntryState]] = { case Reverse(replyTo) =>
    Effect
      .persist(ReversalRequested(DateUtils.now()))
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
    context.log.info(s"Announcing result on Posted")
    resultMessenger(EntrySuccessful(entryId, debitedAccountResultingBalance, creditedAccountResultingBalance))
  }
}
