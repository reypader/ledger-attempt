package io.openledger.domain.account.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.AccountingMode.{CREDIT, DEBIT}
import io.openledger.DateUtils.TimeGen
import io.openledger.LedgerError
import io.openledger.domain.account.Account._
import io.openledger.events._

case class Ready(accountId: String) extends AccountState {
  override def handleEvent(event: AccountEvent)(implicit context: ActorContext[AccountCommand]): AccountState =
    event match {
      case CreditAccountOpened(_) => CreditAccount(accountId, BigDecimal(0), BigDecimal(0), BigDecimal(0))
      case DebitAccountOpened(_) => DebitAccount(accountId, BigDecimal(0), BigDecimal(0), BigDecimal(0))
    }

  override def handleCommand(command: AccountCommand)(implicit context: ActorContext[AccountCommand], transactionMessenger: TransactionMessenger, now: TimeGen): Effect[AccountEvent, AccountState] = {
    context.log.info(s"Handling $command")
    command match {
      case Open(mode) => mode match {
        case CREDIT =>
          Effect.persist(CreditAccountOpened(now()))
            .thenNoReply()
        case DEBIT =>
          Effect.persist(DebitAccountOpened(now()))
            .thenNoReply()
      }
      case c: AccountingCommand =>
        Effect.none.thenRun(_ => transactionMessenger(c.transactionId, AccountingFailed(command.hashCode(), accountId, LedgerError.ACCOUNT_NOT_OPENED)))
      case _ =>
        context.log.warn(s"Unhandled $command")
        Effect.none
    }
  }

  override def availableBalance: BigDecimal = BigDecimal(0)

  override def currentBalance: BigDecimal = BigDecimal(0)
}
