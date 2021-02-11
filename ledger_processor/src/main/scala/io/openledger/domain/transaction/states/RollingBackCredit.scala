package io.openledger.domain.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.domain.account.Account
import io.openledger.domain.transaction.Transaction
import io.openledger.domain.transaction.Transaction._
import io.openledger.events._

case class RollingBackCredit(entryCode: String, transactionId: String, accountToDebit: String, accountToCredit: String, creditedAmount: BigDecimal, amountCaptured: Option[BigDecimal], code: Option[String]) extends TransactionState {
  private val stateCommand = Account.DebitAdjust(transactionId, entryCode, creditedAmount)

  override def handleEvent(event: TransactionEvent)(implicit context: ActorContext[TransactionCommand]): PartialFunction[TransactionEvent, TransactionState] = {
    case DebitAdjustmentDone(creditReversedResultingBalance) => RollingBackDebit(entryCode, transactionId, accountToDebit, accountToCredit, creditedAmount, amountCaptured, code, Some(creditReversedResultingBalance))
    case DebitAdjustmentFailed(_) =>  ResumableRollingBackCredit(this)
  }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): PartialFunction[TransactionCommand, Effect[TransactionEvent, TransactionState]] = {
    case AcceptAccounting(originalCommandHash, accountId, resultingBalance, _) if accountId == accountToCredit && originalCommandHash == stateCommand.hashCode() =>
      Effect.persist(DebitAdjustmentDone(resultingBalance)).thenRun(_.proceed())
    case RejectAccounting(originalCommandHash, accountId, code) if accountId == accountToCredit && originalCommandHash == stateCommand.hashCode() =>
      Effect.persist(DebitAdjustmentFailed(code.toString)).thenRun(_ => context.log.error(s"ALERT: DebitAdjustment failed $code for $accountId"))
  }

  override def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    context.log.info(s"Performing DebitAdjust on $accountToDebit")
    accountMessenger(accountToCredit, stateCommand)
  }
}
