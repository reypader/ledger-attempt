package io.openledger


object LedgerError extends Enumeration {
  val INSUFFICIENT_FUNDS: LedgerError.Value = Value(1, "Insufficient Available Balance")
}
