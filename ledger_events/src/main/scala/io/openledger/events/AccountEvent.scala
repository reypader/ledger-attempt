package io.openledger.events

import io.openledger.TagDistribution

import java.time.OffsetDateTime

object AccountEvent {
  val tagPrefix = "account-"
  val tagDistribution: TagDistribution = TagDistribution(tagPrefix, 100)
}

sealed trait AccountEvent

sealed trait AccountingEvent extends AccountEvent {
  def transactionId: String
}

final case class DebitAccountOpened(timestamp: OffsetDateTime, accountingTags: Set[String]) extends AccountEvent

final case class CreditAccountOpened(timestamp: OffsetDateTime, accountingTags: Set[String]) extends AccountEvent

final case class Debited(transactionId: String, entryCode: String, amount: BigDecimal, newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal, timestamp: OffsetDateTime) extends AccountingEvent

final case class Credited(transactionId: String, entryCode: String, amount: BigDecimal, newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal, timestamp: OffsetDateTime) extends AccountingEvent

final case class DebitAuthorized(transactionId: String, entryCode: String, amount: BigDecimal, newAvailableBalance: BigDecimal, newAuthorizedBalance: BigDecimal, timestamp: OffsetDateTime) extends AccountingEvent

final case class DebitPosted(transactionId: String, entryCode: String, amount: BigDecimal, amountReturned: BigDecimal, newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal, newAuthorizedBalance: BigDecimal, postingTimestamp: OffsetDateTime, timestamp: OffsetDateTime) extends AccountingEvent

final case class Released(transactionId: String, entryCode: String, amountReturned: BigDecimal, newAvailableBalance: BigDecimal, newAuthorizedBalance: BigDecimal, timestamp: OffsetDateTime) extends AccountingEvent

final case class Overdrawn(transactionId: String, entryCode: String, timestamp: OffsetDateTime) extends AccountingEvent

final case class Overpaid(transactionId: String, entryCode: String, timestamp: OffsetDateTime) extends AccountingEvent