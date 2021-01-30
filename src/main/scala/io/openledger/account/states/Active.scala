package io.openledger.account.states

import akka.persistence.typed.scaladsl.{Effect, ReplyEffect}
import io.openledger.LedgerError
import io.openledger.account.Account
import io.openledger.account.Account._
import io.openledger.account.AccountMode.{AccountMode, CREDIT, DEBIT}

case class Active(mode: AccountMode, availableBalance: BigDecimal, currentBalance: BigDecimal) extends AccountState {
  override def handleEvent(event: Account.AccountEvent): AccountState = {
    event match {
      case Debited(newAvailableBalance, newCurrentBalance) => copy(availableBalance = newAvailableBalance, currentBalance = newCurrentBalance)
      case DebitHeld(newAvailableBalance) => copy(availableBalance = newAvailableBalance)
      case Credited(newAvailableBalance, newCurrentBalance) => copy(availableBalance = newAvailableBalance, currentBalance = newCurrentBalance)
      case CreditHeld(newAvailableBalance) => copy(availableBalance = newAvailableBalance)
    }
  }

  override def handleCommand(command: Account.AccountCommand): ReplyEffect[Account.AccountEvent, AccountState] = {
    command match {
      case Debit(amountToDebit, replyTo) =>
        val (newAvailableBalance, newCurrentBalance) = mode match {
          case DEBIT => (availableBalance + amountToDebit, currentBalance + amountToDebit)
          case CREDIT => (availableBalance - amountToDebit, currentBalance - amountToDebit)
        }
        if (newAvailableBalance < 0) {
          Effect.none.thenReply(replyTo)(_ => AdjustmentFailed(LedgerError.INSUFFICIENT_FUNDS))
        } else {
          Effect.persist(Debited(newAvailableBalance, newCurrentBalance))
            .thenReply(replyTo)(_ => AdjustmentSuccessful(newAvailableBalance, newCurrentBalance))
        }
      case DebitHold(amountToDebit, replyTo) =>
        val newAvailableBalance = mode match {
          case DEBIT => availableBalance + amountToDebit
          case CREDIT => availableBalance - amountToDebit
        }
        if (newAvailableBalance < 0) {
          Effect.none.thenReply(replyTo)(_ => AdjustmentFailed(LedgerError.INSUFFICIENT_FUNDS))
        } else {
          Effect.persist(DebitHeld(newAvailableBalance))
            .thenReply(replyTo)(_ => AdjustmentSuccessful(newAvailableBalance, currentBalance))
        }
      case Credit(amountToCredit, replyTo) =>
        val (newAvailableBalance, newCurrentBalance) = mode match {
          case DEBIT => (availableBalance - amountToCredit, currentBalance - amountToCredit)
          case CREDIT => (availableBalance + amountToCredit, currentBalance + amountToCredit)
        }
        if (newAvailableBalance < 0) {
          Effect.none.thenReply(replyTo)(_ => AdjustmentFailed(LedgerError.INSUFFICIENT_FUNDS))
        } else {
          Effect.persist(Credited(newAvailableBalance, newCurrentBalance))
            .thenReply(replyTo)(_ => AdjustmentSuccessful(newAvailableBalance, newCurrentBalance))
        }
      case CreditHold(amountToCredit, replyTo) =>
        val newAvailableBalance = mode match {
          case DEBIT => availableBalance - amountToCredit
          case CREDIT => availableBalance + amountToCredit
        }
        if (newAvailableBalance < 0) {
          Effect.none.thenReply(replyTo)(_ => AdjustmentFailed(LedgerError.INSUFFICIENT_FUNDS))
        } else {
          Effect.persist(CreditHeld(newAvailableBalance))
            .thenReply(replyTo)(_ => AdjustmentSuccessful(newAvailableBalance, currentBalance))
        }
    }
  }
}
