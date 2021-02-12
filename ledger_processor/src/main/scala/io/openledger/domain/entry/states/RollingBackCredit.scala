package io.openledger.domain.entry.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.domain.account.Account
import io.openledger.domain.entry.Entry
import io.openledger.domain.entry.Entry._
import io.openledger.events._

case class RollingBackCredit(
    entryCode: String,
    entryId: String,
    accountToDebit: String,
    accountToCredit: String,
    creditedAmount: BigDecimal,
    amountCaptured: Option[BigDecimal],
    code: Option[String]
) extends EntryState {
  private val stateCommand = Account.DebitAdjust(entryId, entryCode, creditedAmount)

  override def handleEvent(
      event: EntryEvent
  )(implicit context: ActorContext[EntryCommand]): PartialFunction[EntryEvent, EntryState] = {
    case DebitAdjustmentDone(creditReversedResultingBalance) =>
      RollingBackDebit(
        entryCode,
        entryId,
        accountToDebit,
        accountToCredit,
        creditedAmount,
        amountCaptured,
        code,
        Some(creditReversedResultingBalance)
      )
    case DebitAdjustmentFailed(_) => ResumableRollingBackCredit(this)
  }

  override def handleCommand(command: Entry.EntryCommand)(implicit
      context: ActorContext[EntryCommand],
      accountMessenger: AccountMessenger,
      resultMessenger: ResultMessenger
  ): PartialFunction[EntryCommand, Effect[EntryEvent, EntryState]] = {
    case AcceptAccounting(originalCommandHash, accountId, resultingBalance, _)
        if accountId == accountToCredit && originalCommandHash == stateCommand.hashCode() =>
      Effect.persist(DebitAdjustmentDone(resultingBalance)).thenRun(_.proceed())
    case RejectAccounting(originalCommandHash, accountId, code)
        if accountId == accountToCredit && originalCommandHash == stateCommand.hashCode() =>
      Effect
        .persist(DebitAdjustmentFailed(code.toString))
        .thenRun(_ => context.log.error(s"ALERT: DebitAdjustment failed $code for $accountId"))
  }

  override def proceed()(implicit
      context: ActorContext[EntryCommand],
      accountMessenger: AccountMessenger,
      resultMessenger: ResultMessenger
  ): Unit = {
    context.log.info(s"Performing DebitAdjust on $accountToDebit")
    accountMessenger(accountToCredit, stateCommand)
  }
}
