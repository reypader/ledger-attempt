package io.openledger.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.account.Account
import io.openledger.account.Account.{AccountingFailed, AccountingSuccessful}
import io.openledger.transaction.Transaction
import io.openledger.transaction.Transaction.{AccountMessenger, AccountingResult, DebitSucceeded, TransactionCommand}

case class Debiting(transactionId: String, accountToDebit: String, accountToCredit: String, amount: BigDecimal) extends TransactionState {
  override def handleEvent(event: Transaction.TransactionEvent)(implicit context: ActorContext[TransactionCommand]): TransactionState =
    event match {
      case DebitSucceeded() => Crediting(transactionId,accountToCredit, accountToDebit, amount)
    }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand],accountMessenger: AccountMessenger): Effect[Transaction.TransactionEvent, TransactionState] =
    command match{
      case AccountingResult(accounting) if accounting.accountId == accountToDebit => accounting match {
        case AccountingSuccessful(accountId, availableBalance, currentBalance, authorizedBalance) =>
        Effect.persist(DebitSucceeded()).thenRun(_.proceed())
      }
    }

  override def proceed()(implicit context: ActorContext[TransactionCommand],accountMessenger: AccountMessenger): Unit = {
    accountMessenger(accountToDebit, Account.Debit(transactionId, amount))
  }
}
