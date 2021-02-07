package io.openledger.domain.account.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.DateUtils.TimeGen
import io.openledger.LedgerError
import io.openledger.domain.account.Account._
import io.openledger.events._

case class CreditAccount(accountId: String, availableBalance: BigDecimal, currentBalance: BigDecimal, authorizedBalance: BigDecimal) extends AccountState {
  override def handleEvent(event: AccountEvent)(implicit context: ActorContext[AccountCommand]): AccountState = {
    event match {
      case Debited(_, _, _, newAvailableBalance, newCurrentBalance, _) => copy(availableBalance = newAvailableBalance, currentBalance = newCurrentBalance)
      case Credited(_, _, _, newAvailableBalance, newCurrentBalance, _) => copy(availableBalance = newAvailableBalance, currentBalance = newCurrentBalance)
      case DebitAuthorized(_, _, _, newAvailableBalance, newAuthorizedBalance, _) => copy(availableBalance = newAvailableBalance, authorizedBalance = newAuthorizedBalance)
      case DebitPosted(_, _, _, _, newAvailableBalance, newCurrentBalance, newAuthorizedBalance, _, _) => copy(availableBalance = newAvailableBalance, currentBalance = newCurrentBalance, authorizedBalance = newAuthorizedBalance)
      case Released(_, _, _, newAvailableBalance, newAuthorizedBalance, _) => copy(availableBalance = newAvailableBalance, authorizedBalance = newAuthorizedBalance)
      case Overdrawn(_, _, _) => this
    }
  }

  override def handleCommand(command: AccountCommand)(implicit context: ActorContext[AccountCommand], transactionMessenger: TransactionMessenger, now: TimeGen): Effect[AccountEvent, AccountState] = {
    context.log.info(s"Handling $command")
    command match {
      case Debit(transactionId, entryCode, amountToDebit) =>
        val newAvailableBalance = availableBalance - amountToDebit
        val newCurrentBalance = currentBalance - amountToDebit
        if (newAvailableBalance < 0) {
          Effect.none.thenRun(_ => transactionMessenger(transactionId, AccountingFailed(command.hashCode(), accountId, LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE)))
        } else {
          val events = if (newCurrentBalance < 0) {
            Seq(
              Debited(transactionId, entryCode, amountToDebit, newAvailableBalance, newCurrentBalance, now()),
              Overdrawn(transactionId, entryCode, now())
            )
          } else {
            Seq(Debited(transactionId, entryCode, amountToDebit, newAvailableBalance, newCurrentBalance, now()))
          }
          Effect.persist(events)
            .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(command.hashCode(), accountId, newAvailableBalance, newCurrentBalance, authorizedBalance, now())))
        }

      case DebitAdjust(transactionId, entryCode, amountToDebit) =>
        val newAvailableBalance = availableBalance - amountToDebit
        val newCurrentBalance = currentBalance - amountToDebit
        val events = if (newAvailableBalance < 0 || newCurrentBalance < 0) {
          Seq(
            Debited(transactionId, entryCode, amountToDebit, newAvailableBalance, newCurrentBalance, now()),
            Overdrawn(transactionId, entryCode, now())
          )
        } else {
          Seq(Debited(transactionId, entryCode, amountToDebit, newAvailableBalance, newCurrentBalance, now()))
        }
        Effect.persist(events)
          .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(command.hashCode(), accountId, newAvailableBalance, newCurrentBalance, authorizedBalance, now())))

      case Credit(transactionId, entryCode, amountToCredit) =>
        val newAvailableBalance = availableBalance + amountToCredit
        val newCurrentBalance = currentBalance + amountToCredit
        Effect.persist(Credited(transactionId, entryCode, amountToCredit, newAvailableBalance, newCurrentBalance, now()))
          .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(command.hashCode(), accountId, newAvailableBalance, newCurrentBalance, authorizedBalance, now())))

      case CreditAdjust(transactionId, entryCode, amountToCredit) =>
        val newAvailableBalance = availableBalance + amountToCredit
        val newCurrentBalance = currentBalance + amountToCredit
        Effect.persist(Credited(transactionId, entryCode, amountToCredit, newAvailableBalance, newCurrentBalance, now()))
          .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(command.hashCode(), accountId, newAvailableBalance, newCurrentBalance, authorizedBalance, now())))

      case DebitHold(transactionId, entryCode, amountToHold) =>
        val newAvailableBalance = availableBalance - amountToHold
        val newAuthorizedBalance = authorizedBalance + amountToHold
        if (newAvailableBalance < 0) {
          Effect.none.thenRun(_ => transactionMessenger(transactionId, AccountingFailed(command.hashCode(), accountId, LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE)))
        } else {
          Effect.persist(DebitAuthorized(transactionId, entryCode, amountToHold, newAvailableBalance, newAuthorizedBalance, now()))
            .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(command.hashCode(), accountId, newAvailableBalance, currentBalance, newAuthorizedBalance, now())))
        }

      case Post(transactionId, entryCode, amountToCapture, amountToRelease, postingTimestamp) =>
        val newAuthorizedBalance = authorizedBalance - amountToCapture - amountToRelease
        val newCurrentBalance = currentBalance - amountToCapture
        val newAvailableBalance = availableBalance + amountToRelease
        if (newAuthorizedBalance < 0) {
          Effect.none.thenRun(_ => transactionMessenger(transactionId, AccountingFailed(command.hashCode(), accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)))
        } else {
          val events = if (newCurrentBalance < 0) {
            Seq(
              DebitPosted(transactionId, entryCode, amountToCapture, amountToRelease, newAvailableBalance, newCurrentBalance, newAuthorizedBalance, postingTimestamp, now()),
              Overdrawn(transactionId, entryCode, now())
            )
          } else {
            Seq(DebitPosted(transactionId, entryCode, amountToCapture, amountToRelease, newAvailableBalance, newCurrentBalance, newAuthorizedBalance, postingTimestamp, now()))
          }
          Effect.persist(events)
            .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(command.hashCode(), accountId, newAvailableBalance, newCurrentBalance, newAuthorizedBalance, now())))
        }

      case Release(transactionId, entryCode, amountToRelease) =>
        val newAuthorizedBalance = authorizedBalance - amountToRelease
        val newAvailableBalance = availableBalance + amountToRelease
        if (newAuthorizedBalance < 0) {
          Effect.none.thenRun(_ => transactionMessenger(transactionId, AccountingFailed(command.hashCode(), accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)))
        } else {
          Effect.persist(Released(transactionId, entryCode, amountToRelease, newAvailableBalance, newAuthorizedBalance, now()))
            .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(command.hashCode(), accountId, newAvailableBalance, currentBalance, newAuthorizedBalance, now())))
        }

      case _ =>
        context.log.warn(s"Unhandled $command")
        Effect.none
    }
  }
}
