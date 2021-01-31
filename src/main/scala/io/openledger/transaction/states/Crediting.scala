package io.openledger.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.ResultingBalance
import io.openledger.account.Account
import io.openledger.transaction.Transaction
import io.openledger.transaction.Transaction._

import java.time.OffsetDateTime

case class Crediting(transactionId: String, accountToDebit: String, accountToCredit: String, amountAuthorized: BigDecimal, captureAmount: BigDecimal, debitedAccountResultingBalance: ResultingBalance, debitHoldTimestamp: OffsetDateTime) extends TransactionState {
  override def handleEvent(event: Transaction.TransactionEvent)(implicit context: ActorContext[TransactionCommand]): TransactionState =
    event match {
      case CreditSucceeded(creditedAccountResultingBalance) => Posting(transactionId, accountToDebit, accountToCredit, amountAuthorized, captureAmount, debitedAccountResultingBalance, creditedAccountResultingBalance, debitHoldTimestamp)
      case CreditFailed(code) => RollingBackDebit(transactionId, accountToDebit, accountToCredit, amountAuthorized, None, Some(code))
    }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Effect[Transaction.TransactionEvent, TransactionState] = {
    context.log.info(s"Handling $command")
    command match {
      case AcceptAccounting(accountId, resultingBalance, _) if accountId == accountToCredit =>
        Effect.persist(CreditSucceeded(resultingBalance)).thenRun(_.proceed())
      case RejectAccounting(accountId, code) if accountId == accountToCredit =>
        Effect.persist(CreditFailed(code)).thenRun(_.proceed())
      case _ =>
        context.log.warn(s"Unhandled $command")
        Effect.none
    }
  }

  override def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    context.log.info(s"Performing Credit on $accountToCredit")
    accountMessenger(accountToCredit, Account.Credit(transactionId, captureAmount))
    //TODO: Do nothing if auth/capture
  }
}
