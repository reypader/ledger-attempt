package io.openledger


object LedgerError extends Enumeration {
  val INSUFFICIENT_FUNDS: LedgerError.Value = Value(1, "Insufficient Available Balance on DEBIT account (INSUFFICIENT_FUNDS)")
  val OVERPAYMENT: LedgerError.Value = Value(2, "Insufficient Available Balance on CREDIT account (OVERPAYMENT)")
  val UNSUPPORTED_CREDIT_HOLD: LedgerError.Value = Value(3, "Attempted a CreditHold on a CREDIT account")
  val UNSUPPORTED_DEBIT_HOLD: LedgerError.Value = Value(4, "Attempted a DebitHold on a DEBIT account")
}
