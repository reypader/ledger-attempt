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

object EntryProjectionHandler {
  def apply() = new EntryProjectionHandler()
}

class EntryProjectionHandler(
) extends JdbcHandler[EventEnvelope[EntryEvent], PlainJdbcSession] {

  private val separator: Char = '|'
  private def id(envelope: EventEnvelope[EntryEvent]): String =
    envelope.persistenceId.split(separator)(1)

  private def sign(a: AccountMode, b: AccountMode): BigDecimal = if (a == b) 1.0 else -1.0

  override def process(session: PlainJdbcSession, envelope: EventEnvelope[EntryEvent]): Unit =
    session.withConnection { implicit conn =>
      val accountId = id(envelope)
      envelope.event match {
        case Started(entryCode, accountToDebit, accountToCredit, amount, authOnly, timestamp) =>
        case AdjustRequested(entryCode, accountToAdjust, amount, mode)                        =>
        case DebitAuthorizeSucceeded(debitedAccountResultingBalance, timestamp)               =>
        case DebitAuthorizeFailed(code)                                                       =>
        case DebitCaptureSucceeded(debitedAccountResultingBalance)                            =>
        case CreditSucceeded(creditedAccountResultingBalance)                                 =>
        case CreditFailed(code)                                                               =>
        case CreditAdjustmentDone(debitedAccountResultingBalance)                             =>
        case DebitAdjustmentDone(creditedAccountResultingBalance)                             =>
        case CaptureRequested(captureAmount)                                                  =>
        case DebitCaptureFailed(code)                                                         =>
        case CreditAdjustmentFailed(code)                                                     =>
        case DebitAdjustmentFailed(code)                                                      =>
        case Resumed()                                                                        =>
        case ReversalRequested(timestamp)                                                     =>
        case Suspended(timestamp)                                                             =>
        case Done(timestamp)                                                                  =>
      }

    }
}
