package io.openledger.api.http

import io.openledger.AccountingMode.AccountMode

object Operations {

  case class Balance(available: BigDecimal, current: BigDecimal)

  case class AccountResponse(id: String, account_type: AccountMode, accounting_tags: Set[String], balance: Balance)

  case class OpenAccountRequest(account_type: AccountMode, account_id: String, accounting_tags: Set[String])

  case class AdjustRequest(adjustment_type: AccountMode, transaction_id: String, entry_code: String, amount: BigDecimal)

}
