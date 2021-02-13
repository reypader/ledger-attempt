package io.openledger.projection.account

import io.openledger.projection.account.AccountOverdraftRepository.Overdraft
import io.openledger.projection.account.AccountOverdraftRepository.OverdraftType.OverdraftType

import java.sql.Connection
import java.time.OffsetDateTime

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

class AccountOverdraftRepository {
  def save(overdraft: Overdraft)(implicit connection: Connection): Unit = {}

}
