package io.openledger.projection.entry

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
import io.openledger.projection.entry.FloatingEntryRepository.{EntryState, FloatingEntry, UpdatedEntry}

object EntryProjectionHandler {
  def apply(floatingEntryRepository: FloatingEntryRepository) = new EntryProjectionHandler(floatingEntryRepository)
}

class EntryProjectionHandler(
    floatingEntryRepository: FloatingEntryRepository
) extends JdbcHandler[EventEnvelope[EntryEvent], PlainJdbcSession] {

  private val separator: Char = '|'
  private def id(envelope: EventEnvelope[EntryEvent]): String =
    envelope.persistenceId.split(separator)(1)

  override def process(session: PlainJdbcSession, envelope: EventEnvelope[EntryEvent]): Unit =
    session.withConnection { implicit conn =>
      val entryId = id(envelope)
      envelope.event match {
        case Started(entryCode, accountToDebit, accountToCredit, amount, authOnly, timestamp) =>
        //insert into floating simple/auth-only
        //insert into stats
        //insert into floating capture
        case CaptureRequested(captureAmount, timestamp) =>
        //insert into stats
        case ReversalRequested(timestamp) =>
        //insert/update floating reversal in case of premature reversal
        //insert into stats

        case AdjustRequested(entryCode, accountToAdjust, amount, mode) =>
        //insert into floating adjust

        case DebitAuthorizeSucceeded(debitedAccountResultingBalance, timestamp) =>
        //insert into stats uncaptured

        case DebitCaptureSucceeded(debitedAccountResultingBalance) =>
        //remove from stats uncaptured

        case CreditSucceeded(creditedAccountResultingBalance)     =>
        case CreditAdjustmentDone(debitedAccountResultingBalance) =>
        case DebitAdjustmentDone(creditedAccountResultingBalance) =>

        case DebitAuthorizeFailed(code) =>
        //insert into stats
        case CreditFailed(code) =>
        //insert into stats

        case DebitCaptureFailed(code) =>
        //insert into alerts
        case CreditAdjustmentFailed(code) =>
        //insert into alerts
        case DebitAdjustmentFailed(code) =>
        //insert into alerts
        case Resumed() =>
        //remove from alerts

        case Suspended(timestamp) =>
        //insert computed latencies
        //remove floating auth-only
        case Done(timestamp) =>
        //insert computed latencies
        //remove floating capture/simple/reversal/adjust
      }

    }
}
