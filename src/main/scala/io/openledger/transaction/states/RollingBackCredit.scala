package io.openledger.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.LedgerError
import io.openledger.account.Account
import io.openledger.transaction.Transaction
import io.openledger.transaction.Transaction._

case class RollingBackCredit(transactionId: String, accountToDebit: String, accountToCredit: String, creditedAmount: BigDecimal, amountCaptured: Option[BigDecimal], code: Option[LedgerError.Value]) extends TransactionState {
  override def handleEvent(event: Transaction.TransactionEvent)(implicit context: ActorContext[TransactionCommand]): TransactionState =
    event match {
      case DebitAdjustmentDone(debitedAccountResultingBalance) => RollingBackDebit(transactionId, accountToDebit, accountToCredit, creditedAmount, amountCaptured, code)
    }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Effect[Transaction.TransactionEvent, TransactionState] =
    command match {
      case AcceptAccounting(accountId, resultingBalance, _) if accountId == accountToCredit =>
        Effect.persist(DebitAdjustmentDone(resultingBalance)).thenRun(_.proceed())
      case RejectAccounting(accountId, code) if accountId == accountToCredit =>
        Effect.none.thenRun(_ => context.log.error(s"ALERT: DebitAdjustment failed $code for $accountId"))
    }

  override def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    context.log.info(s"Performing DebitAdjust on $accountToDebit")
    accountMessenger(accountToCredit, Account.DebitAdjust(transactionId, creditedAmount))
  }
}
