package io.openledger.domain.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.ResultingBalance
import io.openledger.domain.account.Account
import io.openledger.domain.transaction.Transaction
import io.openledger.domain.transaction.Transaction._
import io.openledger.events._

case class RollingBackDebit(entryCode: String, transactionId: String, accountToDebit: String, accountToCredit: String, authorizedAmount: BigDecimal, amountCaptured: Option[BigDecimal], code: Option[String], creditReversedResultingBalance: Option[ResultingBalance]) extends TransactionState {
  override def handleEvent(event: TransactionEvent)(implicit context: ActorContext[TransactionCommand]): TransactionState =
    event match {
      case CreditAdjustmentDone(debitReversedResultingBalance) => code match {
        case Some(code) => Failed(entryCode, transactionId, code)
        case None => Reversed(entryCode, transactionId, debitReversedResultingBalance, creditReversedResultingBalance)
      }
      case CreditAdjustmentFailed(_) => this
    }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Effect[TransactionEvent, TransactionState] = {
    context.log.info(s"Handling $command in RollingBackDebit")
    command match {
      case AcceptAccounting(originalCommandHash, accountId, resultingBalance, _) if accountId == accountToDebit && originalCommandHash == stateCommand.hashCode() =>
        Effect.persist(CreditAdjustmentDone(resultingBalance)).thenRun(_.proceed())
      case RejectAccounting(originalCommandHash, accountId, code) if accountId == accountToDebit && originalCommandHash == stateCommand.hashCode() =>
        Effect.persist(CreditAdjustmentFailed(code.toString)).thenRun(_ => context.log.error(s"ALERT: CreditAdjustment failed $code for $accountId"))
      case Resume(replyTo) =>
        Effect.none
          .thenRun { next: TransactionState =>
            next.proceed()
            replyTo ! Ack
          }
      case _ =>
        context.log.warn(s"Unhandled $command in RollingBackDebit")
        Effect.none
    }
  }

  private def stateCommand = amountCaptured match {
    case Some(capturedAmount) =>
      Account.CreditAdjust(transactionId, entryCode, capturedAmount)
    case None =>
      Account.Release(transactionId, entryCode, authorizedAmount)
  }

  override def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    context.log.info(s"Performing $stateCommand on $accountToDebit")
    accountMessenger(accountToDebit, stateCommand)
  }
}
