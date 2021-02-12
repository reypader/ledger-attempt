package io.openledger.simulator.sequences

import io.openledger.kafka_operations.EntryRequest.Operation
import io.openledger.kafka_operations._

import java.util.UUID

case class TransferRoundTrip(participants: Seq[String]) extends SequenceGenerator {

  private val pairs = {

    createPairs(participants).flatMap(pair => {
      val txnId = UUID.randomUUID().toString
      Seq(
        EntryRequest(
          Operation.Simple(
            Simple(
              entryCode = "TRANSFER",
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

  override def generate(): Seq[EntryRequest] = pairs
  override def count(): Int = pairs.size

  override def toString: String = s"${pairs.size} transfers rotated among ${participants.size} accounts"
}
