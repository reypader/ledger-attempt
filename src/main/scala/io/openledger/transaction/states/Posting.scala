package io.openledger.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.account.Account
import io.openledger.transaction.Transaction
import io.openledger.transaction.Transaction._

import java.time.OffsetDateTime

case class Posting(transactionId: String, accountToDebit: String, accountToCredit: String, amountAuthorized: BigDecimal, captureAmount:BigDecimal, debitedAccountResultingBalance: ResultingBalance, creditedAccountResultingBalance: ResultingBalance, debitHoldTimestamp: OffsetDateTime) extends TransactionState {
  override def handleEvent(event: Transaction.TransactionEvent)(implicit context: ActorContext[TransactionCommand]): TransactionState =
    event match {
      case DebitPostSucceeded(debitPostedAccountResultingBalance) => Posted(transactionId, accountToDebit, accountToCredit, captureAmount, debitPostedAccountResultingBalance, creditedAccountResultingBalance)
      case DebitPostFailed(code) =>
        context.log.error(s"ALERT: Posting failed $code for $accountToDebit. This should never happen.")
        RollingBackCredit(transactionId, accountToDebit, accountToCredit, captureAmount, None, Some(code))
    }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Effect[Transaction.TransactionEvent, TransactionState] =
    command match {
      case AcceptAccounting(accountId, resultingBalance, timestamp) if accountId == accountToDebit =>
        Effect.persist(DebitPostSucceeded(resultingBalance)).thenRun(_.proceed())
      case RejectAccounting(accountId, code) if accountId == accountToDebit =>
        Effect.persist(DebitPostFailed(code)).thenRun(_.proceed())
    }

  override def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    context.log.info(s"Performing Post on $accountToDebit")
    accountMessenger(accountToDebit, Account.Post(transactionId, captureAmount, amountAuthorized-captureAmount, debitHoldTimestamp))
  }
}
