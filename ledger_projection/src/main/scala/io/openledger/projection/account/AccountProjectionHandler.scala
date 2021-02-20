package io.openledger.projection.account

import akka.projection.eventsourced.EventEnvelope
import akka.projection.jdbc.scaladsl.JdbcHandler
import io.openledger.AccountingMode
import io.openledger.AccountingMode.{AccountMode, CREDIT, DEBIT}
import io.openledger.events._
import io.openledger.projection.PlainJdbcSession
import io.openledger.projection.account.AccountInfoRepository.AccountInfo
import io.openledger.projection.account.AccountOverdraftRepository.Overdraft
import io.openledger.projection.account.AccountOverdraftRepository.OverdraftType.{OVERDRAWN, OVERPAID}
import io.openledger.projection.account.AccountStatementRepository.{AvailableMovement, EntryType, FullMovement}

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

  private val separator: Char = '|'
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

        //TODO: Can (should we?) the following get-then-save patterns be optimized into a single-sql?
        //      Network demand (multiple back and forth) vs DB instance demand (more complex query)?
        case Debited(entryId, entryCode, amount, newAvailableBalance, newCurrentBalance, timestamp) =>
          val info = accountInfoRepository.get(accountId)
          accountStatementRepository.save(
            FullMovement(
              accountId,
              entryId,
              entryCode,
              EntryType.DEBIT,
              sign(info.mode, DEBIT) * amount,
              sign(info.mode, DEBIT) * amount,
              newAvailableBalance,
              newCurrentBalance,
              timestamp,
              timestamp
            )
          )
        //TODO: Can (should we?) the following get-then-save patterns be optimized into a single-sql?
        //      Network demand (multiple back and forth) vs DB instance demand (more complex query)?
        case Credited(entryId, entryCode, amount, newAvailableBalance, newCurrentBalance, timestamp) =>
          val info = accountInfoRepository.get(accountId)
          accountStatementRepository.save(
            FullMovement(
              accountId,
              entryId,
              entryCode,
              EntryType.CREDIT,
              sign(info.mode, CREDIT) * amount,
              sign(info.mode, CREDIT) * amount,
              newAvailableBalance,
              newCurrentBalance,
              timestamp,
              timestamp
            )
          )
        //TODO: Can (should we?) the following get-then-save patterns be optimized into a single-sql?
        //      Network demand (multiple back and forth) vs DB instance demand (more complex query)?
        case DebitAuthorized(entryId, entryCode, amount, newAvailableBalance, _, timestamp) =>
          val info = accountInfoRepository.get(accountId)
          accountStatementRepository.save(
            AvailableMovement(
              accountId,
              entryId,
              entryCode,
              EntryType.DEBIT_AUTHORIZE,
              sign(info.mode, DEBIT) * amount,
              newAvailableBalance,
              timestamp
            )
          )
        //TODO: Can (should we?) the following get-then-save patterns be optimized into a single-sql?
        //      Network demand (multiple back and forth) vs DB instance demand (more complex query)?
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
              EntryType.DEBIT_CAPTURE,
              sign(info.mode, CREDIT) * amountReturned,
              sign(info.mode, DEBIT) * amount,
              newAvailableBalance,
              newCurrentBalance,
              authTimestamp,
              timestamp
            )
          )
        //TODO: Can (should we?) the following get-then-save patterns be optimized into a single-sql?
        //      Network demand (multiple back and forth) vs DB instance demand (more complex query)?
        case DebitReleased(entryId, entryCode, amountReturned, newAvailableBalance, _, timestamp) =>
          val info = accountInfoRepository.get(accountId)
          accountStatementRepository.save(
            AvailableMovement(
              accountId,
              entryId,
              entryCode,
              EntryType.DEBIT_RELEASE,
              sign(info.mode, CREDIT) * amountReturned,
              newAvailableBalance,
              timestamp
            )
          )
      }

    }
}
