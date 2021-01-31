package io.openledger.account.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.LedgerError
import io.openledger.account.Account._

case class DebitAccount(accountId:String, availableBalance: BigDecimal, currentBalance: BigDecimal, authorizedBalance: BigDecimal) extends AccountState {
  override def handleEvent(event: AccountEvent)(implicit context: ActorContext[AccountCommand]): AccountState = {
    event match {
      case Debited(_, newAvailableBalance, newCurrentBalance) => copy(availableBalance = newAvailableBalance, currentBalance = newCurrentBalance)
      case Credited(_, newAvailableBalance, newCurrentBalance) => copy(availableBalance = newAvailableBalance, currentBalance = newCurrentBalance)
      case DebitAuthorized(_, newAvailableBalance, newAuthorizedBalance) => copy(availableBalance = newAvailableBalance, authorizedBalance = newAuthorizedBalance)
      case DebitPosted(_, newAvailableBalance, newCurrentBalance, newAuthorizedBalance) => copy(availableBalance = newAvailableBalance, currentBalance = newCurrentBalance, authorizedBalance = newAuthorizedBalance)
      case Released(_, newAvailableBalance, newAuthorizedBalance) => copy(availableBalance = newAvailableBalance, authorizedBalance = newAuthorizedBalance)
      case Overpaid(_) => this
    }
  }

  override def handleCommand(command: AccountCommand)(implicit context: ActorContext[AccountCommand], transactionMessenger: TransactionMessenger): Effect[AccountEvent, AccountState] = {
    command match {
      case Debit(transactionId, amountToDebit) =>
        val newAvailableBalance = availableBalance + amountToDebit
        val newCurrentBalance = currentBalance + amountToDebit
        Effect.persist(Debited(transactionId, newAvailableBalance, newCurrentBalance))
          .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(accountId, newAvailableBalance, newCurrentBalance, authorizedBalance)))

      case DebitAdjust(transactionId, amountToDebit) =>
        val newAvailableBalance = availableBalance + amountToDebit
        val newCurrentBalance = currentBalance + amountToDebit
        Effect.persist(Debited(transactionId, newAvailableBalance, newCurrentBalance))
          .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(accountId, newAvailableBalance, newCurrentBalance, authorizedBalance)))

      case Credit(transactionId, amountToCredit) =>
        val newAvailableBalance = availableBalance - amountToCredit
        val newCurrentBalance = currentBalance - amountToCredit
        if (newAvailableBalance < 0) {
          Effect.none.thenRun(_ => transactionMessenger(transactionId, AccountingFailed(accountId, LedgerError.INSUFFICIENT_BALANCE)))
        } else {
          val events = if (newCurrentBalance < 0) {
            Seq(
              Credited(transactionId, newAvailableBalance, newCurrentBalance),
              Overpaid(transactionId)
            )
          } else {
            Seq(Credited(transactionId, newAvailableBalance, newCurrentBalance))
          }
          Effect.persist(events)
            .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(accountId, newAvailableBalance, newCurrentBalance, authorizedBalance)))
        }

      case CreditAdjust(transactionId, amountToCredit) =>
        val newAvailableBalance = availableBalance - amountToCredit
        val newCurrentBalance = currentBalance - amountToCredit
        val events = if (newAvailableBalance < 0 || newCurrentBalance < 0) {
          Seq(
            Credited(transactionId, newAvailableBalance, newCurrentBalance),
            Overpaid(transactionId)
          )
        } else {
          Seq(Credited(transactionId, newAvailableBalance, newCurrentBalance))
        }
        Effect.persist(events)
          .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(accountId, newAvailableBalance, newCurrentBalance, authorizedBalance)))

      case DebitHold(transactionId, amountToHold) =>
        val newAvailableBalance = availableBalance + amountToHold
        val newAuthorizedBalance = authorizedBalance + amountToHold
        Effect.persist(DebitAuthorized(transactionId, newAvailableBalance, newAuthorizedBalance))
          .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(accountId, newAvailableBalance, currentBalance, newAuthorizedBalance)))

      case Post(transactionId, amountToCapture, amountToRelease) =>
        val newAuthorizedBalance = authorizedBalance - amountToCapture - amountToRelease
        val newCurrentBalance = currentBalance + amountToCapture
        val newAvailableBalance = availableBalance - amountToRelease
        if (newAuthorizedBalance < 0) {
          Effect.none.thenRun(_ => transactionMessenger(transactionId, AccountingFailed(accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)))
        } else {
          val events = if (newAvailableBalance < 0) {
            Seq(
              DebitPosted(transactionId, newAvailableBalance, newCurrentBalance, newAuthorizedBalance),
              Overpaid(transactionId)
            )
          } else {
            Seq(DebitPosted(transactionId, newAvailableBalance, newCurrentBalance, newAuthorizedBalance))
          }
          Effect.persist(events)
            .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(accountId, newAvailableBalance, newCurrentBalance, newAuthorizedBalance)))
        }

      case Release(transactionId, amountToRelease) =>
        val newAuthorizedBalance = authorizedBalance - amountToRelease
        val newAvailableBalance = availableBalance - amountToRelease
        if (newAuthorizedBalance < 0) {
          Effect.none.thenRun(_ => transactionMessenger(transactionId, AccountingFailed(accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)))
        } else {
          val events = if (newAvailableBalance < 0) {
            Seq(
              Released(transactionId, newAvailableBalance, newAuthorizedBalance),
              Overpaid(transactionId)
            )
          } else {
            Seq(Released(transactionId, newAvailableBalance, newAuthorizedBalance))
          }
          Effect.persist(events)
            .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(accountId, newAvailableBalance, currentBalance, newAuthorizedBalance)))
        }

    }
  }
}
