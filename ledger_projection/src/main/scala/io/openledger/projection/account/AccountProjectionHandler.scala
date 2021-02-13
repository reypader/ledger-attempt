package io.openledger.projection.account

import akka.persistence.typed.PersistenceId
import akka.projection.eventsourced.EventEnvelope
import akka.projection.jdbc.scaladsl.JdbcHandler
import io.openledger.AccountingMode
import io.openledger.AccountingMode.{AccountMode, CREDIT, DEBIT}
import io.openledger.events._
import io.openledger.projection.PlainJdbcSession
import io.openledger.projection.account.AccountInfoRepository.AccountInfo
import io.openledger.projection.account.AccountOverdraftRepository.Overdraft
import io.openledger.projection.account.AccountOverdraftRepository.OverdraftType.{OVERDRAWN, OVERPAID}
import io.openledger.projection.account.AccountStatementRepository.{AvailableMovement, FullMovement, MovementType}

object AccountProjectionHandler {
  def apply(
      accountInfoRepository: AccountInfoRepository,
      accountStatementRepository: AccountStatementRepository,
      accountOverdraftRepository: AccountOverdraftRepository
  ) = new AccountProjectionHandler(accountInfoRepository, accountStatementRepository, accountOverdraftRepository)
}

class AccountProjectionHandler(
    accountInfoRepository: AccountInfoRepository,
    accountStatementRepository: AccountStatementRepository,
    accountOverdraftRepository: AccountOverdraftRepository
) extends JdbcHandler[EventEnvelope[AccountEvent], PlainJdbcSession] {

  private val separator: Char = PersistenceId.DefaultSeparator.charAt(0)
  private def id(envelope: EventEnvelope[AccountEvent]): String =
    envelope.persistenceId.split(separator)(1)

  private def sign(a: AccountMode, b: AccountMode): BigDecimal = if (a == b) 1.0 else -1.0

  override def process(session: PlainJdbcSession, envelope: EventEnvelope[AccountEvent]): Unit =
    session.withConnection { implicit conn =>
      val accountId = id(envelope)
      envelope.event match {
        case DebitAccountOpened(timestamp, accountingTags) =>
          accountInfoRepository.save(
            AccountInfo(
              accountId,
              AccountingMode.DEBIT,
              accountingTags,
              timestamp
            )
          )

        case CreditAccountOpened(timestamp, accountingTags) =>
          accountInfoRepository.save(
            AccountInfo(
              accountId,
              AccountingMode.CREDIT,
              accountingTags,
              timestamp
            )
          )
        case Overdrawn(entryId, entryCode, timestamp) =>
          accountOverdraftRepository.save(
            Overdraft(entryId, entryCode, OVERDRAWN, accountId, timestamp)
          )
        case Overpaid(entryId, entryCode, timestamp) =>
          accountOverdraftRepository.save(
            Overdraft(entryId, entryCode, OVERPAID, accountId, timestamp)
          )

        case Debited(entryId, entryCode, amount, newAvailableBalance, newCurrentBalance, timestamp) =>
          val info = accountInfoRepository.get(accountId)
          accountStatementRepository.save(
            FullMovement(
              accountId,
              entryId,
              entryCode,
              MovementType.DEBIT,
              sign(info.mode, DEBIT) * amount,
              sign(info.mode, DEBIT) * amount,
              newAvailableBalance,
              newCurrentBalance,
              timestamp,
              timestamp
            )
          )
        case Credited(entryId, entryCode, amount, newAvailableBalance, newCurrentBalance, timestamp) =>
          val info = accountInfoRepository.get(accountId)
          accountStatementRepository.save(
            FullMovement(
              accountId,
              entryId,
              entryCode,
              MovementType.CREDIT,
              sign(info.mode, CREDIT) * amount,
              sign(info.mode, CREDIT) * amount,
              newAvailableBalance,
              newCurrentBalance,
              timestamp,
              timestamp
            )
          )
        case DebitAuthorized(entryId, entryCode, amount, newAvailableBalance, _, timestamp) =>
          val info = accountInfoRepository.get(accountId)
          accountStatementRepository.save(
            AvailableMovement(
              accountId,
              entryId,
              entryCode,
              MovementType.DEBIT_AUTHORIZE,
              sign(info.mode, DEBIT) * amount,
              newAvailableBalance,
              timestamp
            )
          )
        case DebitCaptured(
              entryId,
              entryCode,
              amount,
              amountReturned,
              newAvailableBalance,
              newCurrentBalance,
              _,
              authTimestamp,
              timestamp
            ) =>
          val info = accountInfoRepository.get(accountId)
          accountStatementRepository.save(
            FullMovement(
              accountId,
              entryId,
              entryCode,
              MovementType.DEBIT_CAPTURE,
              sign(info.mode, CREDIT) * amountReturned,
              sign(info.mode, DEBIT) * amount,
              newAvailableBalance,
              newCurrentBalance,
              authTimestamp,
              timestamp
            )
          )
        case DebitReleased(entryId, entryCode, amountReturned, newAvailableBalance, _, timestamp) =>
          val info = accountInfoRepository.get(accountId)
          accountStatementRepository.save(
            AvailableMovement(
              accountId,
              entryId,
              entryCode,
              MovementType.DEBIT_RELEASE,
              sign(info.mode, CREDIT) * amountReturned,
              newAvailableBalance,
              timestamp
            )
          )
      }

    }
}
