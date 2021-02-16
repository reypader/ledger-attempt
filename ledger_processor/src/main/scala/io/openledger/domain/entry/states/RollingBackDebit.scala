package io.openledger.domain.entry.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.{DateUtils, ResultingBalance}
import io.openledger.domain.account.Account
import io.openledger.domain.entry.Entry
import io.openledger.domain.entry.Entry._
import io.openledger.events._

case class RollingBackDebit(
    entryCode: String,
    entryId: String,
    accountToDebit: String,
    accountToCredit: String,
    authorizedAmount: BigDecimal,
    amountCaptured: Option[BigDecimal],
    code: Option[String],
    creditReversedResultingBalance: Option[ResultingBalance]
) extends PairedEntry {
  override def handleEvent(
      event: EntryEvent
  )(implicit context: ActorContext[EntryCommand]): PartialFunction[EntryEvent, EntryState] = {
    case CreditAdjustmentDone(debitReversedResultingBalance) =>
      code match {
        case Some(code) => Failed(entryCode, entryId, code)
        case None =>
          Reversed(
            entryCode,
            entryId,
            accountToDebit,
            accountToCredit,
            debitReversedResultingBalance,
            creditReversedResultingBalance
          )
      }
    case CreditAdjustmentFailed(_) => ResumableRollingBackDebit(this)
  }

  override def handleCommand(command: Entry.EntryCommand)(implicit
      context: ActorContext[EntryCommand],
      accountMessenger: AccountMessenger,
      resultMessenger: ResultMessenger
  ): PartialFunction[EntryCommand, Effect[EntryEvent, EntryState]] = {
    case AcceptAccounting(originalCommandHash, accountId, resultingBalance, _)
        if accountId == accountToDebit && originalCommandHash == stateCommand.hashCode() =>
      Effect.persist(Seq(CreditAdjustmentDone(resultingBalance), Done(DateUtils.now()))).thenRun(_.proceed())
    case RejectAccounting(originalCommandHash, accountId, code)
        if accountId == accountToDebit && originalCommandHash == stateCommand.hashCode() =>
      Effect
        .persist(CreditAdjustmentFailed(code.toString))
        .thenRun(_ => context.log.error(s"ALERT: CreditAdjustment failed $code for $accountId"))
  }

  override def proceed()(implicit
      context: ActorContext[EntryCommand],
      accountMessenger: AccountMessenger,
      resultMessenger: ResultMessenger
  ): Unit = {
    context.log.info(s"Performing $stateCommand on $accountToDebit")
    accountMessenger(accountToDebit, stateCommand)
  }

  private def stateCommand = amountCaptured match {
    case Some(capturedAmount) =>
      Account.CreditAdjust(entryId, entryCode, capturedAmount)
    case None =>
      Account.DebitRelease(entryId, entryCode, authorizedAmount)
  }
}
