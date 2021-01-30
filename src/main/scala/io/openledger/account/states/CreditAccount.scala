package io.openledger.account.states

import akka.persistence.typed.scaladsl.{Effect, ReplyEffect}
import io.openledger.LedgerError
import io.openledger.account.Account
import io.openledger.account.Account._

case class CreditAccount(availableBalance: BigDecimal, currentBalance: BigDecimal, authorizedBalance: BigDecimal) extends AccountState {
  override def handleEvent(event: Account.AccountEvent): AccountState = {
    event match {
      case Debited(_, newAvailableBalance, newCurrentBalance) => copy(availableBalance = newAvailableBalance, currentBalance = newCurrentBalance)
      case Credited(_, newAvailableBalance, newCurrentBalance) => copy(availableBalance = newAvailableBalance, currentBalance = newCurrentBalance)
      case Authorized(_, newAvailableBalance, newAuthorizedBalance) => copy(availableBalance = newAvailableBalance, authorizedBalance = newAuthorizedBalance)
      case Captured(_, newAvailableBalance, newCurrentBalance, newAuthorizedBalance) => copy(availableBalance = newAvailableBalance, currentBalance = newCurrentBalance, authorizedBalance = newAuthorizedBalance)
      case Released(_, newAvailableBalance, newAuthorizedBalance) => copy(availableBalance = newAvailableBalance, authorizedBalance = newAuthorizedBalance)
      case Overdrawn(_) => this
    }
  }

  override def handleCommand(command: Account.AccountCommand): ReplyEffect[Account.AccountEvent, AccountState] = {
    command match {
      case Debit(entryId, amountToDebit, replyTo) =>
        val newAvailableBalance = availableBalance - amountToDebit
        val newCurrentBalance = currentBalance - amountToDebit
        if (newAvailableBalance < 0) {
          Effect.none.thenReply(replyTo)(_ => AccountingFailed(entryId, LedgerError.INSUFFICIENT_BALANCE))
        } else {
          val events = if (newCurrentBalance < 0) {
            Seq(
              Debited(entryId, newAvailableBalance, newCurrentBalance),
              Overdrawn(entryId)
            )
          } else {
            Seq(Debited(entryId, newAvailableBalance, newCurrentBalance))
          }
          Effect.persist(events)
            .thenReply(replyTo)(_ => AccountingSuccessful(entryId, newAvailableBalance, newCurrentBalance, authorizedBalance))
        }

      case DebitAdjust(entryId, amountToDebit, replyTo) =>
        val newAvailableBalance = availableBalance - amountToDebit
        val newCurrentBalance = currentBalance - amountToDebit
        val events = if (newAvailableBalance < 0 || newCurrentBalance < 0) {
          Seq(
            Debited(entryId, newAvailableBalance, newCurrentBalance),
            Overdrawn(entryId)
          )
        } else {
          Seq(Debited(entryId, newAvailableBalance, newCurrentBalance))
        }
        Effect.persist(events)
          .thenReply(replyTo)(_ => AccountingSuccessful(entryId, newAvailableBalance, newCurrentBalance, authorizedBalance))

      case Credit(entryId, amountToCredit, replyTo) =>
        val newAvailableBalance = availableBalance + amountToCredit
        val newCurrentBalance = currentBalance + amountToCredit
        Effect.persist(Credited(entryId, newAvailableBalance, newCurrentBalance))
          .thenReply(replyTo)(_ => AccountingSuccessful(entryId, newAvailableBalance, newCurrentBalance, authorizedBalance))

      case CreditAdjust(entryId, amountToCredit, replyTo) =>
        val newAvailableBalance = availableBalance + amountToCredit
        val newCurrentBalance = currentBalance + amountToCredit
        Effect.persist(Credited(entryId, newAvailableBalance, newCurrentBalance))
          .thenReply(replyTo)(_ => AccountingSuccessful(entryId, newAvailableBalance, newCurrentBalance, authorizedBalance))

      case Hold(entryId, amountToHold, replyTo) =>
        val newAvailableBalance = availableBalance - amountToHold
        val newAuthorizedBalance = authorizedBalance + amountToHold
        if (newAvailableBalance < 0) {
          Effect.none.thenReply(replyTo)(_ => AccountingFailed(entryId, LedgerError.INSUFFICIENT_BALANCE))
        } else {
          Effect.persist(Authorized(entryId, newAvailableBalance, newAuthorizedBalance))
            .thenReply(replyTo)(_ => AccountingSuccessful(entryId, newAvailableBalance, currentBalance, newAuthorizedBalance))
        }

      case Capture(entryId, amountToCapture, amountToRelease, replyTo) =>
        val newAuthorizedBalance = authorizedBalance - amountToCapture - amountToRelease
        val newCurrentBalance = currentBalance - amountToCapture
        val newAvailableBalance = availableBalance + amountToRelease
        if (newAuthorizedBalance < 0) {
          Effect.none.thenReply(replyTo)(_ => AccountingFailed(entryId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE))
        } else {
          val events = if (newCurrentBalance < 0) {
            Seq(
              Captured(entryId, newAvailableBalance, newCurrentBalance, newAuthorizedBalance),
              Overdrawn(entryId)
            )
          } else {
            Seq(Captured(entryId, newAvailableBalance, newCurrentBalance, newAuthorizedBalance))
          }
          Effect.persist(events)
            .thenReply(replyTo)(_ => AccountingSuccessful(entryId, newAvailableBalance, newCurrentBalance, newAuthorizedBalance))
        }

      case Release(entryId, amountToRelease, replyTo) =>
        val newAuthorizedBalance = authorizedBalance - amountToRelease
        val newAvailableBalance = availableBalance + amountToRelease
        if (newAuthorizedBalance < 0) {
          Effect.none.thenReply(replyTo)(_ => AccountingFailed(entryId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE))
        } else {
          Effect.persist(Released(entryId, newAvailableBalance, newAuthorizedBalance))
            .thenReply(replyTo)(_ => AccountingSuccessful(entryId, newAvailableBalance, currentBalance, newAuthorizedBalance))
        }
    }
  }
}
