package io.openledger.domain.entry.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.AccountingMode.{AccountMode, CREDIT, DEBIT}
import io.openledger.domain.account.Account
import io.openledger.domain.entry.Entry
import io.openledger.domain.entry.Entry._
import io.openledger.events._

case class Adjusting(entryCode: String, entryId: String, accountToAdjust: String, amount: BigDecimal, mode: AccountMode)
    extends EntryState {
  override def handleEvent(
      event: EntryEvent
  )(implicit context: ActorContext[EntryCommand]): PartialFunction[EntryEvent, EntryState] = {
    case DebitAdjustmentDone(resultingBalance) =>
      Adjusted(entryCode, entryId, accountToAdjust, amount, mode, resultingBalance)
    case CreditAdjustmentDone(resultingBalance) =>
      Adjusted(entryCode, entryId, accountToAdjust, amount, mode, resultingBalance)
    case DebitAdjustmentFailed(code)  => Failed(entryCode, entryId, code)
    case CreditAdjustmentFailed(code) => Failed(entryCode, entryId, code)
  }

  override def handleCommand(command: Entry.EntryCommand)(implicit
      context: ActorContext[EntryCommand],
      accountMessenger: AccountMessenger,
      resultMessenger: ResultMessenger
  ): PartialFunction[EntryCommand, Effect[EntryEvent, EntryState]] = {
    case AcceptAccounting(originalCommandHash, accountId, resultingBalance, timestamp)
        if accountId == accountToAdjust && originalCommandHash == stateCommand.hashCode() && mode == DEBIT =>
      Effect.persist(DebitAdjustmentDone(resultingBalance)).thenRun(_.proceed())
    case AcceptAccounting(originalCommandHash, accountId, resultingBalance, timestamp)
        if accountId == accountToAdjust && originalCommandHash == stateCommand.hashCode() && mode == CREDIT =>
      Effect.persist(CreditAdjustmentDone(resultingBalance)).thenRun(_.proceed())
    case RejectAccounting(originalCommandHash, accountId, code)
        if accountId == accountToAdjust && originalCommandHash == stateCommand.hashCode() && mode == DEBIT =>
      Effect.persist(DebitAdjustmentFailed(code.toString)).thenRun(_.proceed())
    case RejectAccounting(originalCommandHash, accountId, code)
        if accountId == accountToAdjust && originalCommandHash == stateCommand.hashCode() && mode == CREDIT =>
      Effect.persist(CreditAdjustmentFailed(code.toString)).thenRun(_.proceed())
  }

  private def stateCommand = mode match {
    case CREDIT =>
      Account.CreditAdjust(entryId, entryCode, amount)
    case DEBIT =>
      Account.DebitAdjust(entryId, entryCode, amount)
  }

  override def proceed()(implicit
      context: ActorContext[EntryCommand],
      accountMessenger: AccountMessenger,
      resultMessenger: ResultMessenger
  ): Unit = {
    context.log.info(s"Performing $mode Adjustment on $accountToAdjust")
    accountMessenger(accountToAdjust, stateCommand)
  }
}
