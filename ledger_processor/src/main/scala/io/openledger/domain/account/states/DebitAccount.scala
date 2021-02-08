package io.openledger.domain.account.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.DateUtils.TimeGen
import io.openledger.LedgerError
import io.openledger.domain.account.Account._
import io.openledger.events._

case class DebitAccount(accountId: String, availableBalance: BigDecimal, currentBalance: BigDecimal, authorizedBalance: BigDecimal, accountingTags: Set[String]) extends AccountState {
  override def handleEvent(event: AccountEvent)(implicit context: ActorContext[AccountCommand]): PartialFunction[AccountEvent, AccountState] = {
    case Debited(_, _, _, newAvailableBalance, newCurrentBalance, _) => copy(availableBalance = newAvailableBalance, currentBalance = newCurrentBalance)
    case Credited(_, _, _, newAvailableBalance, newCurrentBalance, _) => copy(availableBalance = newAvailableBalance, currentBalance = newCurrentBalance)
    case DebitAuthorized(_, _, _, newAvailableBalance, newAuthorizedBalance, _) => copy(availableBalance = newAvailableBalance, authorizedBalance = newAuthorizedBalance)
    case DebitPosted(_, _, _, _, newAvailableBalance, newCurrentBalance, newAuthorizedBalance, _, _) => copy(availableBalance = newAvailableBalance, currentBalance = newCurrentBalance, authorizedBalance = newAuthorizedBalance)
    case Released(_, _, _, newAvailableBalance, newAuthorizedBalance, _) => copy(availableBalance = newAvailableBalance, authorizedBalance = newAuthorizedBalance)
    case Overpaid(_, _, _) => this
  }

  override def handleCommand(command: AccountCommand)(implicit context: ActorContext[AccountCommand], transactionMessenger: TransactionMessenger, now: TimeGen): PartialFunction[AccountCommand, Effect[AccountEvent, AccountState]] = {
    case Debit(transactionId, entryCode, amountToDebit) =>
      val newAvailableBalance = availableBalance + amountToDebit
      val newCurrentBalance = currentBalance + amountToDebit
      Effect.persist(Debited(transactionId, entryCode, amountToDebit, newAvailableBalance, newCurrentBalance, now()))
        .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(command.hashCode(), accountId, newAvailableBalance, newCurrentBalance, authorizedBalance, now())))

    case DebitAdjust(transactionId, entryCode, amountToDebit) =>
      val newAvailableBalance = availableBalance + amountToDebit
      val newCurrentBalance = currentBalance + amountToDebit
      Effect.persist(Debited(transactionId, entryCode, amountToDebit, newAvailableBalance, newCurrentBalance, now()))
        .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(command.hashCode(), accountId, newAvailableBalance, newCurrentBalance, authorizedBalance, now())))

    case Credit(transactionId, entryCode, amountToCredit) =>
      val newAvailableBalance = availableBalance - amountToCredit
      val newCurrentBalance = currentBalance - amountToCredit
      if (newAvailableBalance < 0) {
        Effect.none.thenRun(_ => transactionMessenger(transactionId, AccountingFailed(command.hashCode(), accountId, LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE)))
      } else {
        val events = if (newCurrentBalance < 0) {
          Seq(
            Credited(transactionId, entryCode, amountToCredit, newAvailableBalance, newCurrentBalance, now()),
            Overpaid(transactionId, entryCode, now())
          )
        } else {
          Seq(Credited(transactionId, entryCode, amountToCredit, newAvailableBalance, newCurrentBalance, now()))
        }
        Effect.persist(events)
          .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(command.hashCode(), accountId, newAvailableBalance, newCurrentBalance, authorizedBalance, now())))
      }

    case CreditAdjust(transactionId, entryCode, amountToCredit) =>
      val newAvailableBalance = availableBalance - amountToCredit
      val newCurrentBalance = currentBalance - amountToCredit
      val events = if (newAvailableBalance < 0 || newCurrentBalance < 0) {
        Seq(
          Credited(transactionId, entryCode, amountToCredit, newAvailableBalance, newCurrentBalance, now()),
          Overpaid(transactionId, entryCode, now())
        )
      } else {
        Seq(Credited(transactionId, entryCode, amountToCredit, newAvailableBalance, newCurrentBalance, now()))
      }
      Effect.persist(events)
        .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(command.hashCode(), accountId, newAvailableBalance, newCurrentBalance, authorizedBalance, now())))

    case DebitHold(transactionId, entryCode, amountToHold) =>
      val newAvailableBalance = availableBalance + amountToHold
      val newAuthorizedBalance = authorizedBalance + amountToHold
      Effect.persist(DebitAuthorized(transactionId, entryCode, amountToHold, newAvailableBalance, newAuthorizedBalance, now()))
        .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(command.hashCode(), accountId, newAvailableBalance, currentBalance, newAuthorizedBalance, now())))

    case Post(transactionId, entryCode, amountToCapture, amountToRelease, postingTimestamp) =>
      val newAuthorizedBalance = authorizedBalance - amountToCapture - amountToRelease
      val newCurrentBalance = currentBalance + amountToCapture
      val newAvailableBalance = availableBalance - amountToRelease
      if (newAuthorizedBalance < 0) {
        Effect.none.thenRun(_ => transactionMessenger(transactionId, AccountingFailed(command.hashCode(), accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)))
      } else {
        val events = if (newAvailableBalance < 0) {
          Seq(
            DebitPosted(transactionId, entryCode, amountToCapture, amountToRelease, newAvailableBalance, newCurrentBalance, newAuthorizedBalance, postingTimestamp, now()),
            Overpaid(transactionId, entryCode, now())
          )
        } else {
          Seq(DebitPosted(transactionId, entryCode, amountToCapture, amountToRelease, newAvailableBalance, newCurrentBalance, newAuthorizedBalance, postingTimestamp, now()))
        }
        Effect.persist(events)
          .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(command.hashCode(), accountId, newAvailableBalance, newCurrentBalance, newAuthorizedBalance, now())))
      }

    case Release(transactionId, entryCode, amountToRelease) =>
      val newAuthorizedBalance = authorizedBalance - amountToRelease
      val newAvailableBalance = availableBalance - amountToRelease
      if (newAuthorizedBalance < 0) {
        Effect.none.thenRun(_ => transactionMessenger(transactionId, AccountingFailed(command.hashCode(), accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)))
      } else {
        val events = if (newAvailableBalance < 0) {
          Seq(
            Released(transactionId, entryCode, amountToRelease, newAvailableBalance, newAuthorizedBalance, now()),
            Overpaid(transactionId, entryCode, now())
          )
        } else {
          Seq(Released(transactionId, entryCode, amountToRelease, newAvailableBalance, newAuthorizedBalance, now()))
        }
        Effect.persist(events)
          .thenRun(_ => transactionMessenger(transactionId, AccountingSuccessful(command.hashCode(), accountId, newAvailableBalance, currentBalance, newAuthorizedBalance, now())))
      }
  }
}
