package io.openledger.domain.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.ResultingBalance
import io.openledger.domain.account.Account
import io.openledger.domain.transaction.Transaction
import io.openledger.domain.transaction.Transaction._
import io.openledger.events._

import java.time.OffsetDateTime

case class Posting(entryCode: String, transactionId: String, accountToDebit: String, accountToCredit: String, amountAuthorized: BigDecimal, captureAmount: BigDecimal, debitedAccountResultingBalance: ResultingBalance, creditedAccountResultingBalance: ResultingBalance, debitHoldTimestamp: OffsetDateTime, reversalPending: Boolean, failedPosting: Boolean = false) extends TransactionState {
  private val stateCommand = Account.Post(transactionId, entryCode, captureAmount, amountAuthorized - captureAmount, debitHoldTimestamp)

  override def handleEvent(event: TransactionEvent)(implicit context: ActorContext[TransactionCommand]): PartialFunction[TransactionEvent, TransactionState] = {
    case DebitPostSucceeded(debitPostedAccountResultingBalance) => Posted(entryCode, transactionId, accountToDebit, accountToCredit, captureAmount, debitPostedAccountResultingBalance, creditedAccountResultingBalance,reversalPending)
    case DebitPostFailed(_) => ResumablePosting(this)
    case ReversalRequested() => copy(reversalPending = true)

  }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): PartialFunction[TransactionCommand, Effect[TransactionEvent, TransactionState]] = {
    case AcceptAccounting(originalCommandHash, accountId, resultingBalance, timestamp) if accountId == accountToDebit && originalCommandHash == stateCommand.hashCode() =>
      Effect.persist(DebitPostSucceeded(resultingBalance)).thenRun(_.proceed())
    case RejectAccounting(originalCommandHash, accountId, code) if accountId == accountToDebit && originalCommandHash == stateCommand.hashCode() =>
      Effect.persist(DebitPostFailed(code.toString)).thenRun(_ => context.log.error(s"ALERT: Posting failed $code for $accountToDebit."))
    case Reverse(replyTo) => Effect.persist(ReversalRequested())
      .thenRun { next: TransactionState =>
        replyTo ! Ack
      }
  }

  override def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    context.log.info(s"Performing Post on $accountToDebit")
    accountMessenger(accountToDebit, stateCommand)
  }
}
