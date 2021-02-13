package io.openledger.domain.account.states

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.Effect
import io.openledger.DateUtils.TimeGen
import io.openledger.LedgerError
import io.openledger.domain.account.Account._
import io.openledger.events._

case class CreditAccount(
    accountId: String,
    availableBalance: BigDecimal,
    currentBalance: BigDecimal,
    authorizedBalance: BigDecimal,
    accountingTags: Set[String]
) extends AccountState {
  override def handleEvent(
      event: AccountEvent
  )(implicit context: ActorContext[AccountCommand]): PartialFunction[AccountEvent, AccountState] = {
    case Debited(_, _, _, newAvailableBalance, newCurrentBalance, _) =>
      copy(availableBalance = newAvailableBalance, currentBalance = newCurrentBalance)
    case Credited(_, _, _, newAvailableBalance, newCurrentBalance, _) =>
      copy(availableBalance = newAvailableBalance, currentBalance = newCurrentBalance)
    case DebitAuthorized(_, _, _, newAvailableBalance, newAuthorizedBalance, _) =>
      copy(availableBalance = newAvailableBalance, authorizedBalance = newAuthorizedBalance)
    case DebitCaptured(_, _, _, _, newAvailableBalance, newCurrentBalance, newAuthorizedBalance, _, _) =>
      copy(
        availableBalance = newAvailableBalance,
        currentBalance = newCurrentBalance,
        authorizedBalance = newAuthorizedBalance
      )
    case DebitReleased(_, _, _, newAvailableBalance, newAuthorizedBalance, _) =>
      copy(availableBalance = newAvailableBalance, authorizedBalance = newAuthorizedBalance)
    case Overdrawn(_, _, _) => this
  }

  override def handleCommand(command: AccountCommand)(implicit
      context: ActorContext[AccountCommand],
      entryMessenger: EntryMessenger,
      now: TimeGen
  ): PartialFunction[AccountCommand, Effect[AccountEvent, AccountState]] = {
    case Debit(entryId, entryCode, amountToDebit) =>
      val newAvailableBalance = availableBalance - amountToDebit
      val newCurrentBalance = currentBalance - amountToDebit
      if (newAvailableBalance < 0) {
        Effect.none.thenRun(_ =>
          entryMessenger(
            entryId,
            AccountingFailed(command.hashCode(), accountId, LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE)
          )
        )
      } else {
        val events = if (newCurrentBalance < 0) {
          Seq(
            Debited(entryId, entryCode, amountToDebit, newAvailableBalance, newCurrentBalance, now()),
            Overdrawn(entryId, entryCode, now())
          )
        } else {
          Seq(Debited(entryId, entryCode, amountToDebit, newAvailableBalance, newCurrentBalance, now()))
        }
        Effect
          .persist(events)
          .thenRun(_ =>
            entryMessenger(
              entryId,
              AccountingSuccessful(
                command.hashCode(),
                accountId,
                newAvailableBalance,
                newCurrentBalance,
                authorizedBalance,
                now()
              )
            )
          )
      }

    case DebitAdjust(entryId, entryCode, amountToDebit) =>
      val newAvailableBalance = availableBalance - amountToDebit
      val newCurrentBalance = currentBalance - amountToDebit
      val events = if (newAvailableBalance < 0 || newCurrentBalance < 0) {
        Seq(
          Debited(entryId, entryCode, amountToDebit, newAvailableBalance, newCurrentBalance, now()),
          Overdrawn(entryId, entryCode, now())
        )
      } else {
        Seq(Debited(entryId, entryCode, amountToDebit, newAvailableBalance, newCurrentBalance, now()))
      }
      Effect
        .persist(events)
        .thenRun(_ =>
          entryMessenger(
            entryId,
            AccountingSuccessful(
              command.hashCode(),
              accountId,
              newAvailableBalance,
              newCurrentBalance,
              authorizedBalance,
              now()
            )
          )
        )

    case Credit(entryId, entryCode, amountToCredit) =>
      val newAvailableBalance = availableBalance + amountToCredit
      val newCurrentBalance = currentBalance + amountToCredit
      Effect
        .persist(Credited(entryId, entryCode, amountToCredit, newAvailableBalance, newCurrentBalance, now()))
        .thenRun(_ =>
          entryMessenger(
            entryId,
            AccountingSuccessful(
              command.hashCode(),
              accountId,
              newAvailableBalance,
              newCurrentBalance,
              authorizedBalance,
              now()
            )
          )
        )

    case CreditAdjust(entryId, entryCode, amountToCredit) =>
      val newAvailableBalance = availableBalance + amountToCredit
      val newCurrentBalance = currentBalance + amountToCredit
      Effect
        .persist(Credited(entryId, entryCode, amountToCredit, newAvailableBalance, newCurrentBalance, now()))
        .thenRun(_ =>
          entryMessenger(
            entryId,
            AccountingSuccessful(
              command.hashCode(),
              accountId,
              newAvailableBalance,
              newCurrentBalance,
              authorizedBalance,
              now()
            )
          )
        )

    case DebitAuthorize(entryId, entryCode, amountToAuthorize) =>
      val newAvailableBalance = availableBalance - amountToAuthorize
      val newAuthorizedBalance = authorizedBalance + amountToAuthorize
      if (newAvailableBalance < 0) {
        Effect.none.thenRun(_ =>
          entryMessenger(
            entryId,
            AccountingFailed(command.hashCode(), accountId, LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE)
          )
        )
      } else {
        Effect
          .persist(
            DebitAuthorized(entryId, entryCode, amountToAuthorize, newAvailableBalance, newAuthorizedBalance, now())
          )
          .thenRun(_ =>
            entryMessenger(
              entryId,
              AccountingSuccessful(
                command.hashCode(),
                accountId,
                newAvailableBalance,
                currentBalance,
                newAuthorizedBalance,
                now()
              )
            )
          )
      }

    case DebitCapture(entryId, entryCode, amountToCapture, amountToRelease, authorizationTimestamp) =>
      val newAuthorizedBalance = authorizedBalance - amountToCapture - amountToRelease
      val newCurrentBalance = currentBalance - amountToCapture
      val newAvailableBalance = availableBalance + amountToRelease
      if (newAuthorizedBalance < 0) {
        Effect.none.thenRun(_ =>
          entryMessenger(
            entryId,
            AccountingFailed(command.hashCode(), accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)
          )
        )
      } else {
        val events = if (newCurrentBalance < 0) {
          Seq(
            DebitCaptured(
              entryId,
              entryCode,
              amountToCapture,
              amountToRelease,
              newAvailableBalance,
              newCurrentBalance,
              newAuthorizedBalance,
              authorizationTimestamp,
              now()
            ),
            Overdrawn(entryId, entryCode, now())
          )
        } else {
          Seq(
            DebitCaptured(
              entryId,
              entryCode,
              amountToCapture,
              amountToRelease,
              newAvailableBalance,
              newCurrentBalance,
              newAuthorizedBalance,
              authorizationTimestamp,
              now()
            )
          )
        }
        Effect
          .persist(events)
          .thenRun(_ =>
            entryMessenger(
              entryId,
              AccountingSuccessful(
                command.hashCode(),
                accountId,
                newAvailableBalance,
                newCurrentBalance,
                newAuthorizedBalance,
                now()
              )
            )
          )
      }

    case DebitRelease(entryId, entryCode, amountToRelease) =>
      val newAuthorizedBalance = authorizedBalance - amountToRelease
      val newAvailableBalance = availableBalance + amountToRelease
      if (newAuthorizedBalance < 0) {
        Effect.none.thenRun(_ =>
          entryMessenger(
            entryId,
            AccountingFailed(command.hashCode(), accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)
          )
        )
      } else {
        Effect
          .persist(
            DebitReleased(entryId, entryCode, amountToRelease, newAvailableBalance, newAuthorizedBalance, now())
          )
          .thenRun(_ =>
            entryMessenger(
              entryId,
              AccountingSuccessful(
                command.hashCode(),
                accountId,
                newAvailableBalance,
                currentBalance,
                newAuthorizedBalance,
                now()
              )
            )
          )
      }
  }

}
