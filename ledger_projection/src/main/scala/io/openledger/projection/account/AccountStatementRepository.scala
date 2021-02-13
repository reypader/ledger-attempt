package io.openledger.projection.account

import io.openledger.projection.account.AccountStatementRepository.EntryType.EntryType
import io.openledger.projection.account.AccountStatementRepository.{AvailableMovement, FullMovement}

import java.sql.Connection
import java.time.OffsetDateTime
import scala.util.Using

object AccountStatementRepository {

  object EntryType extends Enumeration {
    type EntryType = Value
    val DEBIT, CREDIT, DEBIT_AUTHORIZE, DEBIT_CAPTURE, DEBIT_RELEASE = Value
  }

  final case class FullMovement(
      accountId: String,
      entryId: String,
      entryCode: String,
      entryType: EntryType,
      availableBalanceChange: BigDecimal,
      currentBalanceChange: BigDecimal,
      resultingAvailableBalance: BigDecimal,
      resultingCurrentBalance: BigDecimal,
      authTimestamp: OffsetDateTime,
      timestamp: OffsetDateTime
  )

  final case class AvailableMovement(
      accountId: String,
      entryId: String,
      entryCode: String,
      entryType: EntryType,
      availableBalanceChange: BigDecimal,
      resultingAvailableBalance: BigDecimal,
      timestamp: OffsetDateTime
  )

  def apply() = new AccountStatementRepository()
}

//TODO Unit Test
class AccountStatementRepository {
  def save(movement: FullMovement)(implicit connection: Connection): Unit = {
    Using.resource(
      connection.prepareStatement(
        "INSERT INTO ledger_account_statement " +
          "(account_id, " +
          "entry_id, " +
          "entry_code, " +
          "entry_type, " +
          "available_balance_change, " +
          "current_balance_change, " +
          "resulting_available_balance, " +
          "resulting_current_balance, " +
          "authorized_on, " +
          "posted_on) " +
          "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?);"
      )
    ) { statement =>
      statement.setString(1, movement.accountId)
      statement.setString(2, movement.entryId)
      statement.setString(3, movement.entryCode)
      statement.setString(4, movement.entryType.toString)
      statement.setBigDecimal(5, movement.availableBalanceChange.bigDecimal)
      statement.setBigDecimal(6, movement.currentBalanceChange.bigDecimal)
      statement.setBigDecimal(7, movement.resultingAvailableBalance.bigDecimal)
      statement.setBigDecimal(8, movement.resultingCurrentBalance.bigDecimal)
      statement.setObject(9, movement.authTimestamp)
      statement.setObject(10, movement.timestamp)
      statement.execute()
    }
  }
  def save(movement: AvailableMovement)(implicit connection: Connection): Unit = {
    Using.resource(
      connection.prepareStatement(
        "INSERT INTO ledger_account_statement " +
          "(account_id, " +
          "entry_id, " +
          "entry_code, " +
          "entry_type, " +
          "available_balance_change, " +
          "current_balance_change, " +
          "resulting_available_balance, " +
          "resulting_current_balance, " +
          "authorized_on, " +
          "posted_on) " +
          "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?);"
      )
    ) { statement =>
      statement.setString(1, movement.accountId)
      statement.setString(2, movement.entryId)
      statement.setString(3, movement.entryCode)
      statement.setString(4, movement.entryType.toString)
      statement.setBigDecimal(5, movement.availableBalanceChange.bigDecimal)
      statement.setBigDecimal(6, java.math.BigDecimal.ZERO)
      statement.setBigDecimal(7, movement.resultingAvailableBalance.bigDecimal)
      statement.setBigDecimal(8, null)
      statement.setObject(9, null)
      statement.setObject(10, movement.timestamp)
      statement.execute()
    }
  }

}
