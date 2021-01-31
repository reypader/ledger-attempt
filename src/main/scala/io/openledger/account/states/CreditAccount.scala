package io.openledger.account.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.DateUtils.TimeGen
import io.openledger.LedgerError
import io.openledger.account.Account._

case class CreditAccount(accountId: String, availableBalance: BigDecimal, currentBalance: BigDecimal, authorizedBalance: BigDecimal) extends AccountState {
  override def handleEvent(event: AccountEvent)(implicit context: ActorContext[AccountCommand]): AccountState = {
    event match {
      case Debited(_, newAvailableBalance, newCurrentBalance, _) => copy(availableBalance = newAvailableBalance, currentBalance = newCurrentBalance)
      case Credited(_, newAvailableBalance, newCurrentBalance, _) => copy(availableBalance = newAvailableBalance, currentBalance = newCurrentBalance)
      case DebitAuthorized(_, newAvailableBalance, newAuthorizedBalance, _) => copy(availableBalance = newAvailableBalance, authorizedBalance = newAuthorizedBalance)
      case DebitPosted(_, newAvailableBalance, newCurrentBalance, newAuthorizedBalance, _, _) => copy(availableBalance = newAvailableBalance, currentBalance = newCurrentBalance, authorizedBalance = newAuthorizedBalance)
      case Released(_, newAvailableBalance, newAuthorizedBalance, _) => copy(availableBalance = newAvailableBalance, authorizedBalance = newAuthorizedBalance)
      case Overdrawn(_, _) => this
    }
  }

  override def handleCommand(command: AccountCommand)(implicit context: ActorContext[AccountCommand], transactionMessenger: TransactionMessenger, now: TimeGen): Effect[AccountEvent, AccountState] = {
    context.log.info(s"Handling $command")
    command match {
      case Debit(transactionId, amountToDebit) =>
        val newAvailableBalance = availableBalance - amountToDebit
        val newCurrentBalance = currentBalance - amountToDebit
        if (newAvailableBalance < 0) {
          Effect.none.thenRun(_ => transactionMessenger(transactionId, AccountingFailed(accountId, LedgerError.INSUFFICIENT_BALANCE)))
        } else {
          val events = if (newCurrentBalance < 0) {
            Seq(
              Debited(transactionId, newAvailableBalance, newCurrentBalance, now()),
              Overdrawn(transactionId, now())
            )
          } else {
            Seq(Debited(transactionId, newAvailableBalance, newCurrentBalance, now()))
          }
          Effect.persist(events)
            .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(accountId, newAvailableBalance, newCurrentBalance, authorizedBalance, now())))
        }

      case DebitAdjust(transactionId, amountToDebit) =>
        val newAvailableBalance = availableBalance - amountToDebit
        val newCurrentBalance = currentBalance - amountToDebit
        val events = if (newAvailableBalance < 0 || newCurrentBalance < 0) {
          Seq(
            Debited(transactionId, newAvailableBalance, newCurrentBalance, now()),
            Overdrawn(transactionId, now())
          )
        } else {
          Seq(Debited(transactionId, newAvailableBalance, newCurrentBalance, now()))
        }
        Effect.persist(events)
          .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(accountId, newAvailableBalance, newCurrentBalance, authorizedBalance, now())))

      case Credit(transactionId, amountToCredit) =>
        val newAvailableBalance = availableBalance + amountToCredit
        val newCurrentBalance = currentBalance + amountToCredit
        Effect.persist(Credited(transactionId, newAvailableBalance, newCurrentBalance, now()))
          .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(accountId, newAvailableBalance, newCurrentBalance, authorizedBalance, now())))

      case CreditAdjust(transactionId, amountToCredit) =>
        val newAvailableBalance = availableBalance + amountToCredit
        val newCurrentBalance = currentBalance + amountToCredit
        Effect.persist(Credited(transactionId, newAvailableBalance, newCurrentBalance, now()))
          .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(accountId, newAvailableBalance, newCurrentBalance, authorizedBalance, now())))

      case DebitHold(transactionId, amountToHold) =>
        val newAvailableBalance = availableBalance - amountToHold
        val newAuthorizedBalance = authorizedBalance + amountToHold
        if (newAvailableBalance < 0) {
          Effect.none.thenRun(_ => transactionMessenger(transactionId, AccountingFailed(accountId, LedgerError.INSUFFICIENT_BALANCE)))
        } else {
          Effect.persist(DebitAuthorized(transactionId, newAvailableBalance, newAuthorizedBalance, now()))
            .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(accountId, newAvailableBalance, currentBalance, newAuthorizedBalance, now())))
        }

      case Post(transactionId, amountToCapture, amountToRelease, postingTimestamp) =>
        val newAuthorizedBalance = authorizedBalance - amountToCapture - amountToRelease
        val newCurrentBalance = currentBalance - amountToCapture
        val newAvailableBalance = availableBalance + amountToRelease
        if (newAuthorizedBalance < 0) {
          Effect.none.thenRun(_ => transactionMessenger(transactionId, AccountingFailed(accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)))
        } else {
          val events = if (newCurrentBalance < 0) {
            Seq(
              DebitPosted(transactionId, newAvailableBalance, newCurrentBalance, newAuthorizedBalance, postingTimestamp, now()),
              Overdrawn(transactionId, now())
            )
          } else {
            Seq(DebitPosted(transactionId, newAvailableBalance, newCurrentBalance, newAuthorizedBalance, postingTimestamp, now()))
          }
          Effect.persist(events)
            .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(accountId, newAvailableBalance, newCurrentBalance, newAuthorizedBalance, now())))
        }

      case Release(transactionId, amountToRelease) =>
        val newAuthorizedBalance = authorizedBalance - amountToRelease
        val newAvailableBalance = availableBalance + amountToRelease
        if (newAuthorizedBalance < 0) {
          Effect.none.thenRun(_ => transactionMessenger(transactionId, AccountingFailed(accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)))
        } else {
          Effect.persist(Released(transactionId, newAvailableBalance, newAuthorizedBalance, now()))
            .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(accountId, newAvailableBalance, currentBalance, newAuthorizedBalance, now())))
        }

      case _=>
        context.log.warn(s"Unhandled $command")
        Effect.none
    }
  }
}
