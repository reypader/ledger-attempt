package io.openledger.domain.entry.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.ResultingBalance
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
    creditedAccountResultingBalance: ResultingBalance,
    reversalPending: Boolean
) extends EntryState {
  override def handleEvent(event: EntryEvent)(implicit
      context: ActorContext[EntryCommand]
  ): PartialFunction[EntryEvent, EntryState] = { case ReversalRequested() =>
    RollingBackCredit(entryCode, entryId, accountToDebit, accountToCredit, amountCaptured, Some(amountCaptured), None)
  }

  override def handleCommand(command: Entry.EntryCommand)(implicit
      context: ActorContext[EntryCommand],
      accountMessenger: AccountMessenger,
      resultMessenger: ResultMessenger
  ): PartialFunction[EntryCommand, Effect[EntryEvent, EntryState]] = { case Reverse(replyTo) =>
    Effect
      .persist(ReversalRequested())
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

    if (reversalPending) {
      context.log.info(s"Reversal marked on Posted state. Triggering self-reversal")
      context.self ! Reverse(context.system.ignoreRef)
    }
  }
}
