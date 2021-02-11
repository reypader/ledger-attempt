package io.openledger.events

import io.openledger.AccountingMode.AccountMode
import io.openledger.{ResultingBalance, TagDistribution}

import java.time.OffsetDateTime

object TransactionEvent {
  val tagPrefix = "transaction-"
  val tagDistribution: TagDistribution = TagDistribution(tagPrefix, 100)
}

sealed trait TransactionEvent

final case class Started(entryCode: String, accountToDebit: String, accountToCredit: String, amount: BigDecimal, authOnly: Boolean) extends TransactionEvent

final case class AdjustRequested(entryCode: String, accountToAdjust: String, amount: BigDecimal, mode: AccountMode) extends TransactionEvent

final case class DebitHoldSucceeded(debitedAccountResultingBalance: ResultingBalance, timestamp: OffsetDateTime) extends TransactionEvent

final case class DebitHoldFailed(code: String) extends TransactionEvent

final case class DebitPostSucceeded(debitedAccountResultingBalance: ResultingBalance) extends TransactionEvent

final case class CreditSucceeded(creditedAccountResultingBalance: ResultingBalance) extends TransactionEvent

final case class CreditFailed(code: String) extends TransactionEvent

final case class CreditAdjustmentDone(debitedAccountResultingBalance: ResultingBalance) extends TransactionEvent

final case class DebitAdjustmentDone(creditedAccountResultingBalance: ResultingBalance) extends TransactionEvent

final case class ReversalRequested() extends TransactionEvent

final case class CaptureRequested(captureAmount: BigDecimal) extends TransactionEvent

final case class DebitPostFailed(code: String) extends TransactionEvent

final case class CreditAdjustmentFailed(code: String) extends TransactionEvent

final case class DebitAdjustmentFailed(code: String) extends TransactionEvent

final case class Resumed() extends TransactionEvent