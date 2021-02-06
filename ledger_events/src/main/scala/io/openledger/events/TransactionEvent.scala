package io.openledger.events

import io.openledger.ResultingBalance

import java.time.OffsetDateTime

sealed trait TransactionEvent

final case class Started(entryCode: String, accountToDebit: String, accountToCredit: String, amount: BigDecimal, authOnly: Boolean) extends TransactionEvent

final case class DebitHoldSucceeded(debitedAccountResultingBalance: ResultingBalance, timestamp: OffsetDateTime) extends TransactionEvent

final case class DebitHoldFailed(code: String) extends TransactionEvent

final case class DebitPostSucceeded(debitedAccountResultingBalance: ResultingBalance) extends TransactionEvent

final case class CreditSucceeded(creditedAccountResultingBalance: ResultingBalance) extends TransactionEvent

final case class CreditFailed(code: String) extends TransactionEvent

final case class CreditAdjustmentDone(debitedAccountResultingBalance: ResultingBalance) extends TransactionEvent

final case class DebitAdjustmentDone(creditedAccountResultingBalance: ResultingBalance) extends TransactionEvent

final case class ReversalRequested() extends TransactionEvent

final case class CaptureRequested(captureAmount: BigDecimal) extends TransactionEvent

final case class DebitPostFailed() extends TransactionEvent

final case class CreditAdjustmentFailed() extends TransactionEvent

final case class DebitAdjustmentFailed() extends TransactionEvent