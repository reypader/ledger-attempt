package io.openledger.account.states

import akka.persistence.typed.scaladsl.{Effect, ReplyEffect}
import io.openledger.LedgerError
import io.openledger.account.Account
import io.openledger.account.Account._

case class CreditAccount(availableBalance: BigDecimal, currentBalance: BigDecimal, authorizedBalance: BigDecimal) extends AccountState {
  override def handleEvent(event: Account.AccountEvent): AccountState = {
    event match {
      case Debited(newAvailableBalance, newCurrentBalance) => copy(availableBalance = newAvailableBalance, currentBalance = newCurrentBalance)
      case Credited(newAvailableBalance, newCurrentBalance) => copy(availableBalance = newAvailableBalance, currentBalance = newCurrentBalance)
      case Authorized(newAvailableBalance, newAuthorizedBalance) => copy(availableBalance = newAvailableBalance, authorizedBalance = newAuthorizedBalance)
      case Captured(newAvailableBalance, newCurrentBalance, newAuthorizedBalance) => copy(availableBalance = newAvailableBalance, currentBalance = newCurrentBalance, authorizedBalance = newAuthorizedBalance)
      case Released(newAvailableBalance, newAuthorizedBalance) => copy(availableBalance = newAvailableBalance, authorizedBalance = newAuthorizedBalance)
      case Overdraft(newAvailableBalance, newCurrentBalance, newAuthorizedBalance) => copy(availableBalance = newAvailableBalance, currentBalance = newCurrentBalance, authorizedBalance = newAuthorizedBalance)
    }
  }

  override def handleCommand(command: Account.AccountCommand): ReplyEffect[Account.AccountEvent, AccountState] = {
    command match {
      case Debit(amountToDebit, replyTo) =>
        val newAvailableBalance = availableBalance - amountToDebit
        val newCurrentBalance = currentBalance - amountToDebit
        if (newAvailableBalance < 0) {
          Effect.none.thenReply(replyTo)(_ => AdjustmentFailed(LedgerError.INSUFFICIENT_FUNDS))
        } else {
          Effect.persist(Debited(newAvailableBalance, newCurrentBalance))
            .thenReply(replyTo)(_ => AdjustmentSuccessful(newAvailableBalance, newCurrentBalance, authorizedBalance))
        }

      case DebitAdjust(amountToDebit, replyTo) =>
        val newAvailableBalance = availableBalance - amountToDebit
        val newCurrentBalance = currentBalance - amountToDebit
        if (newAvailableBalance < 0 || newCurrentBalance < 0) {
          Effect.persist(Overdraft(newAvailableBalance, newCurrentBalance, authorizedBalance))
            .thenReply(replyTo)(_ => AdjustmentSuccessful(newAvailableBalance, newCurrentBalance, authorizedBalance))
        } else {
          Effect.persist(Debited(newAvailableBalance, newCurrentBalance))
            .thenReply(replyTo)(_ => AdjustmentSuccessful(newAvailableBalance, newCurrentBalance, authorizedBalance))
        }

      case Credit(amountToCredit, replyTo) =>
        val newAvailableBalance = availableBalance + amountToCredit
        val newCurrentBalance = currentBalance + amountToCredit
        Effect.persist(Credited(newAvailableBalance, newCurrentBalance))
          .thenReply(replyTo)(_ => AdjustmentSuccessful(newAvailableBalance, newCurrentBalance, authorizedBalance))

      case CreditAdjust(amountToCredit, replyTo) =>
        val newAvailableBalance = availableBalance + amountToCredit
        val newCurrentBalance = currentBalance + amountToCredit
        Effect.persist(Credited(newAvailableBalance, newCurrentBalance))
          .thenReply(replyTo)(_ => AdjustmentSuccessful(newAvailableBalance, newCurrentBalance, authorizedBalance))

      case Hold(amountToHold, replyTo) =>
        val newAvailableBalance = availableBalance - amountToHold
        val newAuthorizedBalance = authorizedBalance + amountToHold
        if (newAvailableBalance < 0) {
          Effect.none.thenReply(replyTo)(_ => AdjustmentFailed(LedgerError.INSUFFICIENT_FUNDS))
        } else {
          Effect.persist(Authorized(newAvailableBalance, newAuthorizedBalance))
            .thenReply(replyTo)(_ => AdjustmentSuccessful(newAvailableBalance, currentBalance, newAuthorizedBalance))
        }

      case Capture(amountToCapture, amountToRelease, replyTo) =>
        val newAuthorizedBalance = authorizedBalance - amountToCapture - amountToRelease
        val newCurrentBalance = currentBalance - amountToCapture
        val newAvailableBalance = availableBalance + amountToRelease
        if (newAuthorizedBalance < 0) {
          Effect.none.thenReply(replyTo)(_ => AdjustmentFailed(LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE))
        } else if (newCurrentBalance < 0) {
          Effect.persist(Overdraft(newAvailableBalance, newCurrentBalance, newAuthorizedBalance))
            .thenReply(replyTo)(_ => AdjustmentSuccessful(newAvailableBalance, newCurrentBalance, newAuthorizedBalance))
        } else {
          Effect.persist(Captured(newAvailableBalance, newCurrentBalance, newAuthorizedBalance))
            .thenReply(replyTo)(_ => AdjustmentSuccessful(newAvailableBalance, newCurrentBalance, newAuthorizedBalance))
        }
      case Release(amountToRelease, replyTo) =>
        val newAuthorizedBalance = authorizedBalance - amountToRelease
        val newAvailableBalance = availableBalance + amountToRelease
        if (newAuthorizedBalance < 0) {
          Effect.none.thenReply(replyTo)(_ => AdjustmentFailed(LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE))
        } else {
          Effect.persist(Released(newAvailableBalance, newAuthorizedBalance))
            .thenReply(replyTo)(_ => AdjustmentSuccessful(newAvailableBalance, currentBalance, newAuthorizedBalance))
        }

    }
  }
}
