package io.openledger.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.account.Account
import io.openledger.transaction.Transaction
import io.openledger.transaction.Transaction._

case class Debiting(transactionId: String, accountToDebit: String, accountToCredit: String, amount: BigDecimal) extends TransactionState {
  override def handleEvent(event: Transaction.TransactionEvent)(implicit context: ActorContext[TransactionCommand]): TransactionState =
    event match {
      case DebitHoldSucceeded(debitedAccountResultingBalance, timestamp) => Crediting(transactionId, accountToDebit, accountToCredit, amount, amount, debitedAccountResultingBalance, timestamp)
      case DebitHoldFailed(code) => Failed(transactionId, accountToDebit, accountToCredit, amount, code)
    }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Effect[Transaction.TransactionEvent, TransactionState] =
    command match {
      case AcceptAccounting(accountId, resultingBalance, timestamp) if accountId == accountToDebit =>
        Effect.persist(DebitHoldSucceeded(resultingBalance, timestamp)).thenRun(_.proceed())
      case RejectAccounting(accountId, code) if accountId == accountToDebit =>
        Effect.persist(DebitHoldFailed(code)).thenRun(_.proceed())
    }

  override def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    context.log.info(s"Performing Debit on $accountToDebit")
    accountMessenger(accountToDebit, Account.DebitHold(transactionId, amount))
  }
}
