package io.openledger.domain.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.AccountingMode.{AccountMode, CREDIT, DEBIT}
import io.openledger.domain.account.Account
import io.openledger.domain.transaction.Transaction
import io.openledger.domain.transaction.Transaction._
import io.openledger.events._

case class Adjusting(entryCode: String, transactionId: String, accountToAdjust: String, amount: BigDecimal, mode: AccountMode) extends TransactionState {
  override def handleEvent(event: TransactionEvent)(implicit context: ActorContext[TransactionCommand]): PartialFunction[TransactionEvent, TransactionState] = {
    case DebitAdjustmentDone(resultingBalance) => Adjusted(entryCode, transactionId, accountToAdjust, amount, mode, resultingBalance)
    case CreditAdjustmentDone(resultingBalance) => Adjusted(entryCode, transactionId, accountToAdjust, amount, mode, resultingBalance)
    case DebitAdjustmentFailed(code) => Failed(entryCode, transactionId, code)
    case CreditAdjustmentFailed(code) => Failed(entryCode, transactionId, code)
  }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): PartialFunction[TransactionCommand, Effect[TransactionEvent, TransactionState]] = {
    case AcceptAccounting(originalCommandHash, accountId, resultingBalance, timestamp) if accountId == accountToAdjust && originalCommandHash == stateCommand.hashCode() && mode == DEBIT =>
      Effect.persist(DebitAdjustmentDone(resultingBalance)).thenRun(_.proceed())
    case AcceptAccounting(originalCommandHash, accountId, resultingBalance, timestamp) if accountId == accountToAdjust && originalCommandHash == stateCommand.hashCode() && mode == CREDIT =>
      Effect.persist(CreditAdjustmentDone(resultingBalance)).thenRun(_.proceed())
    case RejectAccounting(originalCommandHash, accountId, code) if accountId == accountToAdjust && originalCommandHash == stateCommand.hashCode() && mode == DEBIT =>
      Effect.persist(DebitAdjustmentFailed(code.toString)).thenRun(_.proceed())
    case RejectAccounting(originalCommandHash, accountId, code) if accountId == accountToAdjust && originalCommandHash == stateCommand.hashCode() && mode == CREDIT =>
      Effect.persist(CreditAdjustmentFailed(code.toString)).thenRun(_.proceed())
  }

  private def stateCommand = mode match {
    case CREDIT =>
      Account.CreditAdjust(transactionId, entryCode, amount)
    case DEBIT =>
      Account.DebitAdjust(transactionId, entryCode, amount)
  }

  override def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    context.log.info(s"Performing $mode Adjustment on $accountToAdjust")
    accountMessenger(accountToAdjust, stateCommand)
  }
}
