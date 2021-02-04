package io.openledger.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.account.Account
import io.openledger.transaction.Transaction
import io.openledger.transaction.Transaction._

case class Authorizing(entryCode: String, transactionId: String, accountToDebit: String, accountToCredit: String, amountAuthorized: BigDecimal, authOnly: Boolean) extends TransactionState {
  override def handleEvent(event: Transaction.TransactionEvent)(implicit context: ActorContext[TransactionCommand]): TransactionState =
    event match {
      case DebitHoldSucceeded(debitedAccountResultingBalance, timestamp) =>
        if (authOnly) {
          Pending(entryCode, transactionId, accountToDebit, accountToCredit, amountAuthorized, debitedAccountResultingBalance, timestamp)
        } else {
          Crediting(entryCode, transactionId, accountToDebit, accountToCredit, amountAuthorized, amountAuthorized, debitedAccountResultingBalance, timestamp)
        }
      case DebitHoldFailed(code) => Failed(entryCode, transactionId, accountToDebit, accountToCredit, amountAuthorized, code)
    }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Effect[Transaction.TransactionEvent, TransactionState] = {
    context.log.info(s"Handling $command in Authorizing")
    command match {
      case AcceptAccounting(accountId, resultingBalance, timestamp) if accountId == accountToDebit =>
        Effect.persist(DebitHoldSucceeded(resultingBalance, timestamp)).thenRun(_.proceed())
      case RejectAccounting(accountId, code) if accountId == accountToDebit =>
        Effect.persist(DebitHoldFailed(code)).thenRun(_.proceed())
      case _ =>
        context.log.warn(s"Unhandled $command in Authorizing")
        Effect.none
    }
  }

  override def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    context.log.info(s"Performing DebitHold on $accountToDebit")
    accountMessenger(accountToDebit, Account.DebitHold(transactionId, entryCode, amountAuthorized))
  }
}
