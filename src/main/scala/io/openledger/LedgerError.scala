package io.openledger


object LedgerError extends Enumeration {
  val INSUFFICIENT_FUNDS: LedgerError.Value = Value(1, "Insufficient Available Balance on DEBIT account (INSUFFICIENT_FUNDS)")
  val OVERPAYMENT: LedgerError.Value = Value(2, "Insufficient Available Balance on CREDIT account (OVERPAYMENT)")
  val OVERDRAFT: LedgerError.Value = Value(3, "Capture leads to a negative Current Balance (OVERDRAFT)")
  val INSUFFICIENT_AUTHORIZED_FUNDS : LedgerError.Value = Value(4,"Capture leads to a negative Authorized Balance. THIS IS VERY BAD (INSUFFICIENT_AUTHORIZED_FUNDS)")
}
