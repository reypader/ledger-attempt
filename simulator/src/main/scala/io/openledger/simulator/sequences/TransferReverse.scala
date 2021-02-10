package io.openledger.simulator.sequences

import io.openledger.kafka_operations.TransactionRequest.Operation
import io.openledger.kafka_operations._

import java.util.UUID

case class TransferReverse(participants: Seq[String]) extends SequenceGenerator {

  private val pairs = {

    createPairs(participants).flatMap(pair => {
      val txnId = UUID.randomUUID().toString
      Seq(
        TransactionRequest(
          Operation.Simple(
            Simple(entryCode = "TRANSFER_REV", transactionId = txnId, accountToDebit = pair._1, accountToCredit = pair._2, amount = 1)
          )
        )
      )
    })
  }

  private val transactions = reverse(pairs)

  override def generate(): Seq[TransactionRequest] = transactions
  override def count(): Int = transactions.size
}
