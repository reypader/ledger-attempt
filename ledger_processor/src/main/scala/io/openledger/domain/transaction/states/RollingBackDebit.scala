package io.openledger.domain.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.domain.account.Account
import io.openledger.domain.transaction.Transaction
import io.openledger.domain.transaction.Transaction._
import io.openledger.events._

case class RollingBackDebit(entryCode: String, transactionId: String, accountToDebit: String, accountToCredit: String, authorizedAmount: BigDecimal, amountCaptured: Option[BigDecimal], code: Option[String]) extends TransactionState {
  override def handleEvent(event: TransactionEvent)(implicit context: ActorContext[TransactionCommand]): TransactionState =
    event match {
      case CreditAdjustmentDone(debitedAccountResultingBalance) => code match {
        case Some(code) => Failed(entryCode, transactionId, accountToDebit, accountToCredit, authorizedAmount, code)
        case None => Reversed(entryCode, transactionId)
      }
      case CreditAdjustmentFailed() => this
    }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Effect[TransactionEvent, TransactionState] = {
    context.log.info(s"Handling $command in RollingBackDebit")
    command match {
      case AcceptAccounting(originalCommandHash, accountId, resultingBalance, _) if accountId == accountToDebit && originalCommandHash == stateCommand.hashCode() =>
        Effect.persist(CreditAdjustmentDone(resultingBalance)).thenRun(_.proceed())
      case RejectAccounting(originalCommandHash, accountId, code) if accountId == accountToDebit && originalCommandHash == stateCommand.hashCode() =>
        Effect.persist(CreditAdjustmentFailed()).thenRun(_ => context.log.error(s"ALERT: CreditAdjustment failed $code for $accountId"))
      case Resume() =>
        Effect.none.thenRun(_ => proceed())
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
