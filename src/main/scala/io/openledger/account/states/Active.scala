package io.openledger.account.states

import akka.persistence.typed.scaladsl.{Effect, ReplyEffect}
import io.openledger.LedgerError
import io.openledger.account.Account
import io.openledger.account.Account._
import io.openledger.account.AccountMode.{AccountMode, CREDIT, DEBIT}

case class Active(mode: AccountMode, availableBalance: BigDecimal, currentBalance: BigDecimal, reservedBalance: BigDecimal) extends AccountState {
  override def handleEvent(event: Account.AccountEvent): AccountState = {
    event match {
      case Debited(newAvailableBalance, newCurrentBalance) => copy(availableBalance = newAvailableBalance, currentBalance = newCurrentBalance)
      case DebitHeld(newAvailableBalance, newReservedBalance) => copy(availableBalance = newAvailableBalance, reservedBalance = newReservedBalance)
      case Credited(newAvailableBalance, newCurrentBalance) => copy(availableBalance = newAvailableBalance, currentBalance = newCurrentBalance)
      case CreditHeld(newAvailableBalance, newReservedBalance) => copy(availableBalance = newAvailableBalance, reservedBalance = newReservedBalance)
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
            .thenReply(replyTo)(_ => AdjustmentSuccessful(newAvailableBalance, newCurrentBalance, reservedBalance))
        }
      case DebitHold(amountToDebit, replyTo) => mode match {
        case DEBIT => Effect.none.thenReply(replyTo)(_ => AdjustmentFailed(LedgerError.UNSUPPORTED_DEBIT_HOLD))
        case CREDIT => {
          val newAvailableBalance = availableBalance - amountToDebit
          val newReservedBalance = reservedBalance + amountToDebit
          if (newAvailableBalance < 0) {
            Effect.none.thenReply(replyTo)(_ => AdjustmentFailed(LedgerError.INSUFFICIENT_FUNDS))
          } else {
            Effect.persist(DebitHeld(newAvailableBalance, newReservedBalance))
              .thenReply(replyTo)(_ => AdjustmentSuccessful(newAvailableBalance, currentBalance, newReservedBalance))
          }
        }
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
            .thenReply(replyTo)(_ => AdjustmentSuccessful(newAvailableBalance, newCurrentBalance, reservedBalance))
        }
      case CreditHold(amountToCredit, replyTo) => mode match {
        case DEBIT => {
          val newAvailableBalance = availableBalance - amountToCredit
          val newReservedBalance = reservedBalance + amountToCredit
          if (newAvailableBalance < 0) {
            Effect.none.thenReply(replyTo)(_ => AdjustmentFailed(LedgerError.OVERPAYMENT))
          } else {
            Effect.persist(DebitHeld(newAvailableBalance, newReservedBalance))
              .thenReply(replyTo)(_ => AdjustmentSuccessful(newAvailableBalance, currentBalance, newReservedBalance))
          }
        }
        case CREDIT => Effect.none.thenReply(replyTo)(_ => AdjustmentFailed(LedgerError.UNSUPPORTED_CREDIT_HOLD))
      }
    }
  }
}
