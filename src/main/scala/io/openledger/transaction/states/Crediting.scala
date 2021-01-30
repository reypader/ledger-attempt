package io.openledger.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.account.Account
import io.openledger.account.Account.AccountingSuccessful
import io.openledger.transaction.Transaction
import io.openledger.transaction.Transaction.{AccountMessenger, AccountingResult, CreditSucceeded, TransactionCommand}

case class Crediting(transactionId: String, accountToDebit: String, accountToCredit: String, amount: BigDecimal) extends TransactionState {
  override def handleEvent(event: Transaction.TransactionEvent)(implicit context: ActorContext[TransactionCommand]): TransactionState =
    event match {
      case CreditSucceeded() => Posted()
    }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand],accountMessenger: AccountMessenger): Effect[Transaction.TransactionEvent, TransactionState] =
    command match {
      case AccountingResult(accounting) if accounting.accountId == accountToDebit => accounting match {
        case AccountingSuccessful(accountId, availableBalance, currentBalance, authorizedBalance) =>
          Effect.persist(CreditSucceeded()).thenRun(_.proceed())
      }
    }

  override def proceed()(implicit context: ActorContext[TransactionCommand],accountMessenger: AccountMessenger): Unit = {
    accountMessenger(accountToDebit, Account.Credit(transactionId, amount))
  }
}
