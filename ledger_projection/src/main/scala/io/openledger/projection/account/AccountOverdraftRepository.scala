package io.openledger.projection.account

import io.openledger.projection.account.AccountOverdraftRepository.Overdraft
import io.openledger.projection.account.AccountOverdraftRepository.OverdraftType.OverdraftType

import java.sql.Connection
import java.time.OffsetDateTime
import scala.util.Using

object AccountOverdraftRepository {

  object OverdraftType extends Enumeration {
    type OverdraftType = Value
    val OVERDRAWN, OVERPAID = Value
  }

  final case class Overdraft(
      entryId: String,
      entryCode: String,
      overdraftType: OverdraftType,
      accountId: String,
      timestamp: OffsetDateTime
  )

  def apply() = new AccountOverdraftRepository()
}

//TODO Unit Test
class AccountOverdraftRepository {
  def save(overdraft: Overdraft)(implicit connection: Connection): Unit = {
    Using.resource(
      connection.prepareStatement(
        "INSERT INTO ledger_account_overdraft_events " +
          "(account_id, entry_id, entry_code, overdraft_type, happened_on) " +
          "VALUES(?, ?, ?, ?, ?);"
      )
    ) { infoStatement =>
      infoStatement.setString(1, overdraft.accountId)
      infoStatement.setString(2, overdraft.entryId)
      infoStatement.setString(3, overdraft.entryCode)
      infoStatement.setString(4, overdraft.overdraftType.toString)
      infoStatement.setObject(5, overdraft.timestamp)
      infoStatement.execute()
    }
  }

}
