package io.openledger


object LedgerError extends Enumeration {
  type LedgerError = Value
  val INSUFFICIENT_BALANCE, INSUFFICIENT_AUTHORIZED_BALANCE, CAPTURE_MORE_THAN_AUTHORIZED = Value
}
