package io.openledger.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.account.Account
import io.openledger.transaction.Transaction
import io.openledger.transaction.Transaction._

case class Debiting(transactionId: String, accountToDebit: String, accountToCredit: String, amount: BigDecimal) extends TransactionState {
  override def handleEvent(event: Transaction.TransactionEvent)(implicit context: ActorContext[TransactionCommand]): TransactionState =
    event match {
      case DebitSucceeded(debitedAccountResultingBalance) => Crediting(transactionId, accountToDebit, accountToCredit, amount, debitedAccountResultingBalance)
      case DebitFailed(code) => Failed(transactionId, accountToDebit, accountToCredit, amount, code)
    }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Effect[Transaction.TransactionEvent, TransactionState] =
    command match {
      case AcceptAccounting(accountId, resultingBalance) if accountId == accountToDebit =>
        Effect.persist(DebitSucceeded(resultingBalance)).thenRun(_.proceed())
      case RejectAccounting(accountId, code) if accountId == accountToDebit =>
        Effect.persist(DebitFailed(code)).thenRun(_.proceed())
    }

  override def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    context.log.info(s"Performing Debit on $accountToDebit")
    accountMessenger(accountToDebit, Account.Debit(transactionId, amount))
  }
}
