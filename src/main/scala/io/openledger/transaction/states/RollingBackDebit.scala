package io.openledger.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.LedgerError
import io.openledger.account.Account
import io.openledger.transaction.Transaction
import io.openledger.transaction.Transaction._

case class RollingBackDebit(transactionId: String, accountToDebit: String, accountToCredit: String, authorizedAmount: BigDecimal, amountCaptured: Option[BigDecimal], code: Option[LedgerError.Value]) extends TransactionState {
  override def handleEvent(event: Transaction.TransactionEvent)(implicit context: ActorContext[TransactionCommand]): TransactionState =
    event match {
      case CreditAdjustmentDone(debitedAccountResultingBalance) => code match {
        case Some(code) => Failed(transactionId, accountToDebit, accountToCredit, authorizedAmount, code)
        case None => Reversed(transactionId)
      }
    }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Effect[Transaction.TransactionEvent, TransactionState] = {
    context.log.info(s"Handling $command")
    command match {
      case AcceptAccounting(accountId, resultingBalance, _) if accountId == accountToDebit =>
        Effect.persist(CreditAdjustmentDone(resultingBalance)).thenRun(_.proceed())
      case RejectAccounting(accountId, code) if accountId == accountToDebit =>
        Effect.none.thenRun(_ => context.log.error(s"ALERT: CreditAdjustment failed $code for $accountId"))
      case _ =>
        context.log.warn(s"Unhandled $command")
        Effect.none
    }
  }

  override def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    amountCaptured match {
      case Some(capturedAmount) =>
        context.log.info(s"Performing CreditAdjust on $accountToDebit")
        accountMessenger(accountToDebit, Account.CreditAdjust(transactionId, capturedAmount))
      case None =>
        context.log.info(s"Performing Release on $accountToDebit")
        accountMessenger(accountToDebit, Account.Release(transactionId, authorizedAmount))
    }
  }
}
