package io.openledger.domain.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.ResultingBalance
import io.openledger.domain.account.Account
import io.openledger.domain.transaction.Transaction
import io.openledger.domain.transaction.Transaction._
import io.openledger.events._

import java.time.OffsetDateTime

case class Crediting(entryCode: String, transactionId: String, accountToDebit: String, accountToCredit: String, amountAuthorized: BigDecimal, captureAmount: BigDecimal, debitedAccountResultingBalance: ResultingBalance, debitHoldTimestamp: OffsetDateTime,reversalPending:Boolean) extends TransactionState {
  private val stateCommand = Account.Credit(transactionId, entryCode, captureAmount)

  override def handleEvent(event: TransactionEvent)(implicit context: ActorContext[TransactionCommand]): PartialFunction[TransactionEvent, TransactionState] = {
    case CreditSucceeded(creditedAccountResultingBalance) => Posting(entryCode, transactionId, accountToDebit, accountToCredit, amountAuthorized, captureAmount, debitedAccountResultingBalance, creditedAccountResultingBalance, debitHoldTimestamp,reversalPending)
    case CreditFailed(code) => RollingBackDebit(entryCode, transactionId, accountToDebit, accountToCredit, amountAuthorized, None, Some(code), None)
    case ReversalRequested() => copy(reversalPending = true)
  }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): PartialFunction[TransactionCommand, Effect[TransactionEvent, TransactionState]] = {
    case AcceptAccounting(originalCommandHash, accountId, resultingBalance, _) if accountId == accountToCredit && originalCommandHash == stateCommand.hashCode() =>
      Effect.persist(CreditSucceeded(resultingBalance)).thenRun(_.proceed())
    case RejectAccounting(originalCommandHash, accountId, code) if accountId == accountToCredit && originalCommandHash == stateCommand.hashCode() =>
      Effect.persist(CreditFailed(code.toString)).thenRun(_.proceed())
    case Reverse(replyTo) => Effect.persist(ReversalRequested())
      .thenRun { next: TransactionState =>
        replyTo ! Ack
      }
  }

  override def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    context.log.info(s"Performing Credit on $accountToCredit")
    accountMessenger(accountToCredit, stateCommand)
  }
}
