package io.openledger.simulator.sequences

import io.openledger.kafka_operations.EntryRequest.Operation
import io.openledger.kafka_operations._

import java.util.UUID

case class TransferReverse(participants: Seq[String]) extends SequenceGenerator {

  private val pairs = {

    createPairs(participants).flatMap(pair => {
      val txnId = UUID.randomUUID().toString
      Seq(
        EntryRequest(
          Operation.Simple(
            Simple(
              entryCode = "TRANSFER_REV",
              entryId = txnId,
              accountToDebit = pair._1,
              accountToCredit = pair._2,
              amount = 1
            )
          )
        )
      )
    })
  }

  private val entries = reverse(pairs)

  override def generate(): Seq[EntryRequest] = entries
  override def count(): Int = entries.size

  override def toString: String =
    s"${entries.count(r => r.operation.isSimple)} transfers rotated among ${participants.size} accounts followed by ${entries
      .count(r => r.operation.isReverse)} reversals"

}
