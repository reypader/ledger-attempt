package io.openledger.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.account.Account
import io.openledger.transaction.Transaction
import io.openledger.transaction.Transaction._

case class Crediting(transactionId: String, accountToDebit: String, accountToCredit: String, amount: BigDecimal, debitedAccountResultingBalance: ResultingBalance) extends TransactionState {
  override def handleEvent(event: Transaction.TransactionEvent)(implicit context: ActorContext[TransactionCommand]): TransactionState =
    event match {
      case CreditSucceeded(creditedAccountResultingBalance) => Posted(transactionId, accountToDebit, accountToCredit, amount, debitedAccountResultingBalance, creditedAccountResultingBalance)
      case CreditFailed(code) => RollingBackDebit(transactionId, accountToDebit, accountToCredit, amount, Some(code))
    }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Effect[Transaction.TransactionEvent, TransactionState] =
    command match {
      case AcceptAccounting(accountId, resultingBalance) if accountId == accountToCredit =>
        Effect.persist(CreditSucceeded(resultingBalance)).thenRun(_.proceed())
      case RejectAccounting(accountId, code) if accountId == accountToCredit =>
        Effect.persist(CreditFailed(code)).thenRun(_.proceed())
    }

  override def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    context.log.info(s"Performing Credit on $accountToCredit")
    accountMessenger(accountToCredit, Account.Credit(transactionId, amount))
  }
}
