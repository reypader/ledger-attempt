package io.openledger.simulator.sequences

import io.openledger.kafka_operations.TransactionRequest.Operation
import io.openledger.kafka_operations._

import java.util.UUID

case class AuthCaptureReverse(participants: Seq[String]) extends SequenceGenerator {

  private val authCap: Map[String, Seq[TransactionRequest]] = {

    createPairs(participants).flatMap(pair => {
      val txnId = UUID.randomUUID().toString
      Seq(
        TransactionRequest(
          Operation.Authorize(
            Authorize(entryCode = "AUTH_CAP", transactionId = txnId, accountToDebit = pair._1, accountToCredit = pair._2, amount = 1)
          )
        ),
        TransactionRequest(
          Operation.Simple(
            Simple(entryCode = "TRANSFER_BETWEEN1", transactionId = UUID.randomUUID().toString, accountToDebit = pair._1, accountToCredit = pair._2, amount = 1)
          )
        ),
        TransactionRequest(
          Operation.Simple(
            Simple(entryCode = "TRANSFER_BETWEEN2", transactionId = UUID.randomUUID().toString, accountToDebit = pair._1, accountToCredit = pair._2, amount = 1)
          )
        )
        ,
        TransactionRequest(
          Operation.Capture(
            Capture(transactionId = txnId, amountToCapture = 1)
          )
        )
      )
    }
    )
  }.groupBy(r => r.operation match {
    case Operation.Authorize(_) => "AUTH"
    case Operation.Simple(_) => "SIMPLE"
    case Operation.Capture(_) => "CAPTURE"
    case _ => throw new IllegalArgumentException()
  })

  private val transactions = reverse(authCap("AUTH") ++ authCap("SIMPLE") ++ authCap("CAPTURE"))

  override def generate(): Seq[TransactionRequest] = transactions

  override def count(): Int = transactions.size
  override def toString: String = s"${transactions.count(r => r.operation.isAuthorize)} auths rotated among ${participants.size} accounts followed by ${transactions.count(r => r.operation.isSimple)} transfers and ${transactions.count(r => r.operation.isCapture)} full captures and ${transactions.count(r => r.operation.isReverse)} reversals"
}
