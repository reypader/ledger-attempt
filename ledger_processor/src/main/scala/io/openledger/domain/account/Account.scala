package io.openledger.domain.account

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import io.openledger.AccountingMode.AccountMode
import io.openledger.DateUtils.TimeGen
import io.openledger.domain.account.states.{AccountState, Ready}
import io.openledger.events.AccountEvent
import io.openledger.{LedgerError, LedgerSerializable}

import java.time.OffsetDateTime

object Account {
  type TransactionMessenger = (String, AccountingStatus) => Unit
  val AccountTypeKey: EntityTypeKey[AccountCommand] = EntityTypeKey[Account.AccountCommand]("Account")

  def apply(accountId: String)(implicit messenger: TransactionMessenger, timeGen: TimeGen): Behavior[AccountCommand] =
    Behaviors.setup { implicit actorContext: ActorContext[AccountCommand] =>
      EventSourcedBehavior[AccountCommand, AccountEvent, AccountState](
        persistenceId = PersistenceId.of(AccountTypeKey.name, accountId),
        emptyState = Ready(accountId),
        commandHandler = (state, cmd) => {
          actorContext.log.info(s"Handling command $cmd")
          state.handleCommand(cmd).orElse[AccountCommand, Effect[AccountEvent, AccountState]] {
            case Get(replyTo) => Effect.none.thenReply(replyTo)(s => s)
            case c: AccountingCommand =>
              actorContext.log.warn(s"Unhandled command $cmd")
              Effect.none.thenRun { _ =>
                messenger(c.transactionId, AccountingFailed(c.hashCode(), accountId, LedgerError.UNSUPPORTED_ACCOUNT_OPERATION_ON_CURRENT_STATE))
              }
            case _ =>
              actorContext.log.warn(s"Unhandled command $cmd")
              Effect.none
          }(cmd)
        },
        eventHandler = (state, evt) => {
          actorContext.log.info(s"Handling event $evt")
          state.handleEvent(evt).orElse[AccountEvent, AccountState] {
            case _ =>
              actorContext.log.warn(s"Unhandled event $evt")
              state
          }(evt)
        })
    }

  sealed trait AccountCommand extends LedgerSerializable

  sealed trait AccountingCommand extends AccountCommand {
    def transactionId: String
  }


  sealed trait AccountingStatus extends LedgerSerializable {
    def accountId: String
  }

  final case class Open(mode: AccountMode, accountingTags: Set[String]) extends AccountCommand

  final case class Debit(transactionId: String, entryCode: String, amountToDebit: BigDecimal) extends AccountingCommand

  final case class DebitAdjust(transactionId: String, entryCode: String, amountToDebit: BigDecimal) extends AccountingCommand

  final case class Credit(transactionId: String, entryCode: String, amountToCredit: BigDecimal) extends AccountingCommand

  final case class CreditAdjust(transactionId: String, entryCode: String, amountToCredit: BigDecimal) extends AccountingCommand

  final case class DebitHold(transactionId: String, entryCode: String, amountToHold: BigDecimal) extends AccountingCommand

  final case class Post(transactionId: String, entryCode: String, amountToCapture: BigDecimal, amountToRelease: BigDecimal, postingTimestamp: OffsetDateTime) extends AccountingCommand

  final case class Release(transactionId: String, entryCode: String, amountToRelease: BigDecimal) extends AccountingCommand

  final case class Get(replyTo: ActorRef[AccountState]) extends AccountCommand

  final case class AccountingSuccessful(commandHash: Int, accountId: String, availableBalance: BigDecimal, currentBalance: BigDecimal, authorizedBalance: BigDecimal, timestamp: OffsetDateTime) extends AccountingStatus

  final case class AccountingFailed(commandHash: Int, accountId: String, code: LedgerError.Value) extends AccountingStatus


}
