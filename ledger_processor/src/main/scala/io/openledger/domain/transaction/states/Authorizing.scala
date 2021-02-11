package io.openledger.domain.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.domain.account.Account
import io.openledger.domain.transaction.Transaction
import io.openledger.domain.transaction.Transaction._
import io.openledger.events._

case class Authorizing(entryCode: String, transactionId: String, accountToDebit: String, accountToCredit: String, amountAuthorized: BigDecimal, authOnly: Boolean, reversalPending: Boolean) extends TransactionState {
  private val stateCommand = Account.DebitHold(transactionId, entryCode, amountAuthorized)

  override def handleEvent(event: TransactionEvent)(implicit context: ActorContext[TransactionCommand]): PartialFunction[TransactionEvent, TransactionState] = {
    case DebitHoldSucceeded(debitedAccountResultingBalance, timestamp) =>
      if (authOnly) {
        Pending(entryCode, transactionId, accountToDebit, accountToCredit, amountAuthorized, debitedAccountResultingBalance, timestamp, reversalPending)
      } else {
        Crediting(entryCode, transactionId, accountToDebit, accountToCredit, amountAuthorized, amountAuthorized, debitedAccountResultingBalance, timestamp, reversalPending)
      }
    case DebitHoldFailed(code) => Failed(entryCode, transactionId, code)
    case ReversalRequested() => copy(reversalPending = true)
  }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): PartialFunction[TransactionCommand, Effect[TransactionEvent, TransactionState]] = {
    case AcceptAccounting(originalCommandHash, accountId, resultingBalance, timestamp) if accountId == accountToDebit && originalCommandHash == stateCommand.hashCode() =>
      Effect.persist(DebitHoldSucceeded(resultingBalance, timestamp)).thenRun(_.proceed())
    case RejectAccounting(originalCommandHash, accountId, code) if accountId == accountToDebit && originalCommandHash == stateCommand.hashCode() =>
      Effect.persist(DebitHoldFailed(code.toString)).thenRun(_.proceed())
    case Reverse(replyTo) => Effect.persist(ReversalRequested())
      .thenRun { next: TransactionState =>
        replyTo ! Ack
      }
  }

  override def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    context.log.info(s"Performing DebitHold on $accountToDebit")
    accountMessenger(accountToDebit, stateCommand)
  }
}
