package io.openledger.projection.account

import io.openledger.AccountingMode.{AccountMode, DEBIT}
import io.openledger.projection.account.AccountInfoRepository.AccountInfo

import java.sql.Connection
import java.time.OffsetDateTime

object AccountInfoRepository {
  final case class AccountInfo(id: String, mode: AccountMode, accountingTag: Set[String], openedOn: OffsetDateTime)

  def apply() = new AccountInfoRepository()
}

class AccountInfoRepository {
  def save(accoungInfo: AccountInfo)(implicit connection: Connection): Unit = {}
  def get(accountId: String)(implicit connection: Connection): AccountInfo = {
    AccountInfo("", DEBIT, Set.empty, OffsetDateTime.now())
  }
}
