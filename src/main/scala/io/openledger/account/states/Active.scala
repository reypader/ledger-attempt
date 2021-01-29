package io.openledger.account.states

import akka.persistence.typed.scaladsl.{Effect, ReplyEffect}
import io.openledger.LedgerError
import io.openledger.account.Account
import io.openledger.account.Account._
import io.openledger.account.AccountMode.{AccountMode, CREDIT, DEBIT}

case class Active(mode: AccountMode, availableBalance: BigDecimal) extends AccountState {
  override def handleEvent(event: Account.AccountEvent): AccountState = {
    event match {
      case Debited(newAvailableBalance) => copy(availableBalance = newAvailableBalance)
      case Credited(newAvailableBalance) => copy(availableBalance = newAvailableBalance)
    }
  }

  override def handleCommand(command: Account.AccountCommand): ReplyEffect[Account.AccountEvent, AccountState] = {
    command match {
      case Debit(amountToDebit, replyTo) =>
        val newAvailableBalance = mode match {
          case DEBIT => availableBalance + amountToDebit
          case CREDIT => availableBalance - amountToDebit
        }
        if (newAvailableBalance < 0) {
          Effect.none.thenReply(replyTo)(_ => AdjustmentFailed(LedgerError.INSUFFICIENT_FUNDS))
        } else {
          Effect.persist(Debited(newAvailableBalance)).thenReply(replyTo)(_ => AdjustmentSuccessful(newAvailableBalance))
        }
      case Credit(amountToCredit, replyTo) =>
        val newAvailableBalance = mode match {
          case DEBIT => availableBalance - amountToCredit
          case CREDIT => availableBalance + amountToCredit
        }
        if (newAvailableBalance < 0) {
          Effect.none.thenReply(replyTo)(_ => AdjustmentFailed(LedgerError.INSUFFICIENT_FUNDS))
        } else {
          Effect.persist(Credited(newAvailableBalance)).thenReply(replyTo)(_ => AdjustmentSuccessful(newAvailableBalance))
        }
    }
  }
}
