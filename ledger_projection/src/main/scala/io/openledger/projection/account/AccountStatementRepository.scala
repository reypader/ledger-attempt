package io.openledger.projection.account

import io.openledger.projection.account.AccountStatementRepository.MovementType.MovementType
import io.openledger.projection.account.AccountStatementRepository.{AvailableMovement, FullMovement}

import java.sql.Connection
import java.time.OffsetDateTime

object AccountStatementRepository {

  object MovementType extends Enumeration {
    type MovementType = Value
    val DEBIT, CREDIT, DEBIT_AUTHORIZE, DEBIT_CAPTURE, DEBIT_RELEASE = Value
  }

  final case class FullMovement(
      accountId: String,
      entryId: String,
      entryCode: String,
      movementType: MovementType,
      availableBalanceMovement: BigDecimal,
      currentBalanceMovement: BigDecimal,
      resultingAvailableBalance: BigDecimal,
      resultingCurrentBalance: BigDecimal,
      authTimestamp: OffsetDateTime,
      timestamp: OffsetDateTime
  )

  final case class AvailableMovement(
      accountId: String,
      entryId: String,
      entryCode: String,
      movementType: MovementType,
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
