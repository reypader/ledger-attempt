package io.openledger.events

import java.time.OffsetDateTime

sealed trait AccountEvent

sealed trait AccountingEvent extends AccountEvent {
  def transactionId: String
}

final case class DebitAccountOpened(timestamp: OffsetDateTime) extends AccountEvent

final case class CreditAccountOpened(timestamp: OffsetDateTime) extends AccountEvent

final case class Debited(transactionId: String, entryCode: String, newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal, timestamp: OffsetDateTime) extends AccountingEvent

final case class Credited(transactionId: String, entryCode: String, newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal, timestamp: OffsetDateTime) extends AccountingEvent

final case class DebitAuthorized(transactionId: String, entryCode: String, newAvailableBalance: BigDecimal, newAuthorizedBalance: BigDecimal, timestamp: OffsetDateTime) extends AccountingEvent

final case class DebitPosted(transactionId: String, entryCode: String, newAvailableBalance: BigDecimal, newCurrentBalance: BigDecimal, newAuthorizedBalance: BigDecimal, postingTimestamp: OffsetDateTime, timestamp: OffsetDateTime) extends AccountingEvent

final case class Released(transactionId: String, entryCode: String, newAvailableBalance: BigDecimal, newAuthorizedBalance: BigDecimal, timestamp: OffsetDateTime) extends AccountingEvent

final case class Overdrawn(transactionId: String, entryCode: String, timestamp: OffsetDateTime) extends AccountingEvent

final case class Overpaid(transactionId: String, entryCode: String, timestamp: OffsetDateTime) extends AccountingEvent