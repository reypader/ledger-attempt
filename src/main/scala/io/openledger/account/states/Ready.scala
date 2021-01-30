package io.openledger.account.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.account.Account._
import io.openledger.account.AccountMode.{CREDIT, DEBIT}

case class Ready() extends AccountState {
  override def handleEvent(event: AccountEvent)(implicit context: ActorContext[AccountCommand]): AccountState =
    event match {
      case CreditAccountOpened() => CreditAccount(BigDecimal(0), BigDecimal(0), BigDecimal(0))
      case DebitAccountOpened() => DebitAccount(BigDecimal(0), BigDecimal(0), BigDecimal(0))
    }

  override def handleCommand(command: AccountCommand)(implicit context: ActorContext[AccountCommand], transactionMessenger: TransactionMessenger): Effect[AccountEvent, AccountState] =
    command match {
      case Open(mode) => mode match {
        case CREDIT =>
          Effect.persist(CreditAccountOpened())
            .thenNoReply()
        case DEBIT =>
          Effect.persist(DebitAccountOpened())
            .thenNoReply()
      }
    }
}
