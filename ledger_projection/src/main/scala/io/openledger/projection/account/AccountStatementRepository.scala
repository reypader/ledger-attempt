package io.openledger.projection.account

import io.openledger.projection.account.AccountStatementRepository.{AvailableMovement, FullMovement}

import java.sql.Connection
import java.time.OffsetDateTime

object AccountStatementRepository {

  final case class FullMovement(
      entryId: String,
      entryCode: String,
      availableBalanceMovement: BigDecimal,
      currentBalanceMovement: BigDecimal,
      resultingAvailableBalance: BigDecimal,
      resultingCurrentBalance: BigDecimal,
      timestamp: OffsetDateTime
  )

  final case class AvailableMovement(
      entryId: String,
      entryCode: String,
      availableBalanceMovement: BigDecimal,
      resultingAvailableBalance: BigDecimal,
      timestamp: OffsetDateTime
  )

  def apply() = new AccountStatementRepository()
}

class AccountStatementRepository {
  def save(movement: FullMovement)(implicit connection: Connection): Unit = {}
  def save(movement: AvailableMovement)(implicit connection: Connection): Unit = {}

}
