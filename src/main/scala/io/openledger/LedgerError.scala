package io.openledger


object LedgerError extends Enumeration {
  val INSUFFICIENT_BALANCE: LedgerError.Value = Value(1, "Insufficient Available Balance")
  val INSUFFICIENT_AUTHORIZED_BALANCE: LedgerError.Value = Value(2, "Capture leads to a negative Authorized Balance. THIS IS VERY BAD AND SHOULD NOT HAPPEN (INSUFFICIENT_AUTHORIZED_FUNDS)")
  val CAPTURE_MORE_THAN_AUTHORIZED: LedgerError.Value = Value(3, "Attempted to capture amount that was more than authorized in the transaction.")
}
