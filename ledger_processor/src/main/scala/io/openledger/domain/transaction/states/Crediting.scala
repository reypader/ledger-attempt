package io.openledger.domain.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.domain.account.Account
import io.openledger.domain.transaction.Transaction
import io.openledger.domain.transaction.Transaction._
import io.openledger.events._
import io.openledger.{LedgerError, ResultingBalance}

import java.time.OffsetDateTime

case class Crediting(entryCode: String, transactionId: String, accountToDebit: String, accountToCredit: String, amountAuthorized: BigDecimal, captureAmount: BigDecimal, debitedAccountResultingBalance: ResultingBalance, debitHoldTimestamp: OffsetDateTime) extends TransactionState {
  private val stateCommand = Account.Credit(transactionId, entryCode, captureAmount)

  override def handleEvent(event: TransactionEvent)(implicit context: ActorContext[TransactionCommand]): TransactionState =
    event match {
      case CreditSucceeded(creditedAccountResultingBalance) => Posting(entryCode, transactionId, accountToDebit, accountToCredit, amountAuthorized, captureAmount, debitedAccountResultingBalance, creditedAccountResultingBalance, debitHoldTimestamp)
      case CreditFailed(code) => RollingBackDebit(entryCode, transactionId, accountToDebit, accountToCredit, amountAuthorized, None, Some(code), None)
    }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Effect[TransactionEvent, TransactionState] = {
    context.log.info(s"Handling $command in Crediting")
    command match {
      case AcceptAccounting(originalCommandHash, accountId, resultingBalance, _) if accountId == accountToCredit && originalCommandHash == stateCommand.hashCode() =>
        Effect.persist(CreditSucceeded(resultingBalance)).thenRun(_.proceed())
      case RejectAccounting(originalCommandHash, accountId, code) if accountId == accountToCredit && originalCommandHash == stateCommand.hashCode() =>
        Effect.persist(CreditFailed(code.toString)).thenRun(_.proceed())
      case ackable: Ackable =>
        context.log.warn(s"Unhandled Ackable $command in Crediting. NACK")
        Effect.none
          .thenRun { _ =>
            ackable.replyTo ! Nack
            resultMessenger(CommandRejected(transactionId, LedgerError.UNSUPPORTED_OPERATION))
          }
      case _ =>
        context.log.warn(s"Unhandled $command in Crediting")
        Effect.none
    }
  }

  override def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    context.log.info(s"Performing Credit on $accountToCredit")
    accountMessenger(accountToCredit, stateCommand)
  }
}
