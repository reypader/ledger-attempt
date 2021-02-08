package io.openledger.domain.account.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.AccountingMode.{CREDIT, DEBIT}
import io.openledger.DateUtils.TimeGen
import io.openledger.domain.account.Account._
import io.openledger.events._

case class Ready(accountId: String) extends AccountState {
  override def handleEvent(event: AccountEvent)(implicit context: ActorContext[AccountCommand]): PartialFunction[AccountEvent, AccountState] = {
    case CreditAccountOpened(_, accountingTags) => CreditAccount(accountId, BigDecimal(0), BigDecimal(0), BigDecimal(0), accountingTags)
    case DebitAccountOpened(_, accountingTags) => DebitAccount(accountId, BigDecimal(0), BigDecimal(0), BigDecimal(0), accountingTags)
  }

  override def handleCommand(command: AccountCommand)(implicit context: ActorContext[AccountCommand], transactionMessenger: TransactionMessenger, now: TimeGen): PartialFunction[AccountCommand, Effect[AccountEvent, AccountState]] = {
    case Open(mode, accountingTags) => mode match {
      case CREDIT =>
        Effect.persist(CreditAccountOpened(now(), accountingTags))
          .thenNoReply()
      case DEBIT =>
        Effect.persist(DebitAccountOpened(now(), accountingTags))
          .thenNoReply()
    }
  }

  override def availableBalance: BigDecimal = BigDecimal(0)

  override def currentBalance: BigDecimal = BigDecimal(0)
}
