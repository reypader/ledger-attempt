package io.openledger.domain.transaction.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.LedgerError
import io.openledger.domain.account.Account
import io.openledger.domain.transaction.Transaction
import io.openledger.domain.transaction.Transaction._
import io.openledger.events._

case class Authorizing(entryCode: String, transactionId: String, accountToDebit: String, accountToCredit: String, amountAuthorized: BigDecimal, authOnly: Boolean) extends TransactionState {
  private val stateCommand = Account.DebitHold(transactionId, entryCode, amountAuthorized)

  override def handleEvent(event: TransactionEvent)(implicit context: ActorContext[TransactionCommand]): TransactionState =
    event match {
      case DebitHoldSucceeded(debitedAccountResultingBalance, timestamp) =>
        if (authOnly) {
          Pending(entryCode, transactionId, accountToDebit, accountToCredit, amountAuthorized, debitedAccountResultingBalance, timestamp)
        } else {
          Crediting(entryCode, transactionId, accountToDebit, accountToCredit, amountAuthorized, amountAuthorized, debitedAccountResultingBalance, timestamp)
        }
      case DebitHoldFailed(code) => Failed(entryCode, transactionId, code)
    }

  override def handleCommand(command: Transaction.TransactionCommand)(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Effect[TransactionEvent, TransactionState] = {
    context.log.info(s"Handling $command in Authorizing")
    command match {
      case AcceptAccounting(originalCommandHash, accountId, resultingBalance, timestamp) if accountId == accountToDebit && originalCommandHash == stateCommand.hashCode() =>
        Effect.persist(DebitHoldSucceeded(resultingBalance, timestamp)).thenRun(_.proceed())
      case RejectAccounting(originalCommandHash, accountId, code) if accountId == accountToDebit && originalCommandHash == stateCommand.hashCode() =>
        Effect.persist(DebitHoldFailed(code.toString)).thenRun(_.proceed())
      case ackable: Ackable =>
        context.log.warn(s"Unhandled Ackable $command in Authorizing. NACK")
        Effect.none
          .thenRun { _ =>
            ackable.replyTo ! Nack
            resultMessenger(CommandRejected(transactionId, LedgerError.UNSUPPORTED_OPERATION))
          }
      case _ =>
        context.log.warn(s"Unhandled $command in Authorizing")
        Effect.none
    }
  }

  override def proceed()(implicit context: ActorContext[TransactionCommand], accountMessenger: AccountMessenger, resultMessenger: ResultMessenger): Unit = {
    context.log.info(s"Performing DebitHold on $accountToDebit")
    accountMessenger(accountToDebit, stateCommand)
  }
}
