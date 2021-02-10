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
          Operation.Capture(
            Capture(transactionId = txnId, amountToCapture = 1)
          )
        )
      )
    }
    )
  }.groupBy(r => r.operation match {
    case Operation.Authorize(_) => "AUTH"
    case Operation.Capture(_) => "CAPTURE"
    case _ => throw new IllegalArgumentException()
  })

  private val transactions = reverse(authCap("AUTH") ++ authCap("CAPTURE"))

  override def generate(): Seq[TransactionRequest] = transactions

  override def count(): Int = transactions.size
}
