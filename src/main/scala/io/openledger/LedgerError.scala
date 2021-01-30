package io.openledger


object LedgerError extends Enumeration {
  val INSUFFICIENT_BALANCE: LedgerError.Value = Value(1, "Insufficient Available Balance")
  val INSUFFICIENT_AUTHORIZED_BALANCE: LedgerError.Value = Value(4, "Capture leads to a negative Authorized Balance. THIS IS VERY BAD (INSUFFICIENT_AUTHORIZED_FUNDS)")
}
