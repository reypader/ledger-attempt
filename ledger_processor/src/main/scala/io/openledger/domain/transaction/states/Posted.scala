package io.openledger.domain.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.ResultingBalance
import io.openledger.domain.transaction.Transaction
import io.openledger.domain.transaction.Transaction._
import io.openledger.events._

case class Posted(entryCode: String, transactionId: String, accountToDebit: String, accountToCredit: String, amountCaptured: BigDecimal, debitedAccountResultingBalance: ResultingBalance, creditedAccountResultingBalance: ResultingBalance,reversalPending:Boolean) extends TransactionState {
  override def handleEvent(event: TransactionEvent)(implicit context: ActorContext[TransactionCommand]): PartialFunction[TransactionEvent, TransactionState] = {
    case ReversalRequested() => RollingBackCredit(entryCode, transactionId, accountToDebit, accountToCredit, amountCaptured, Some(amountCaptured), None)
  }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): PartialFunction[TransactionCommand, Effect[TransactionEvent, TransactionState]] = {
    case Reverse(replyTo) => Effect.persist(ReversalRequested())
      .thenRun { next: TransactionState =>
        next.proceed()
        replyTo ! Ack
      }
  }

  override def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    context.log.info(s"Announcing result on Posted")
    resultMessenger(TransactionSuccessful(transactionId, debitedAccountResultingBalance, creditedAccountResultingBalance))

    if (reversalPending){
      context.log.info(s"Reversal marked on Posted state. Triggering self-reversal")
      context.self ! Reverse(context.system.ignoreRef)
    }
  }
}
