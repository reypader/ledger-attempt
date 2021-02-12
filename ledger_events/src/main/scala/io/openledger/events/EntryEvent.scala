package io.openledger.events

import io.openledger.AccountingMode.AccountMode
import io.openledger.{ResultingBalance, TagDistribution}

import java.time.OffsetDateTime

object EntryEvent {
  val tagPrefix = "entry-"
  val tagDistribution: TagDistribution = TagDistribution(tagPrefix, 100)
}

sealed trait EntryEvent

final case class Started(
    entryCode: String,
    accountToDebit: String,
    accountToCredit: String,
    amount: BigDecimal,
    authOnly: Boolean
) extends EntryEvent

final case class AdjustRequested(entryCode: String, accountToAdjust: String, amount: BigDecimal, mode: AccountMode)
    extends EntryEvent

final case class DebitHoldSucceeded(debitedAccountResultingBalance: ResultingBalance, timestamp: OffsetDateTime)
    extends EntryEvent

final case class DebitHoldFailed(code: String) extends EntryEvent

final case class DebitPostSucceeded(debitedAccountResultingBalance: ResultingBalance) extends EntryEvent

final case class CreditSucceeded(creditedAccountResultingBalance: ResultingBalance) extends EntryEvent

final case class CreditFailed(code: String) extends EntryEvent

final case class CreditAdjustmentDone(debitedAccountResultingBalance: ResultingBalance) extends EntryEvent

final case class DebitAdjustmentDone(creditedAccountResultingBalance: ResultingBalance) extends EntryEvent

final case class ReversalRequested() extends EntryEvent

final case class CaptureRequested(captureAmount: BigDecimal) extends EntryEvent

final case class DebitPostFailed(code: String) extends EntryEvent

final case class CreditAdjustmentFailed(code: String) extends EntryEvent

final case class DebitAdjustmentFailed(code: String) extends EntryEvent

final case class Resumed() extends EntryEvent
