package io.openledger.simulator.sequences

import io.openledger.kafka_operations.EntryRequest.Operation
import io.openledger.kafka_operations.{Reverse, EntryRequest}
import org.slf4j.LoggerFactory

trait SequenceGenerator {

  def createPairs(participants: Seq[String]): Seq[(String, String)] = {
    var pairs = Seq[(String, String)]()
    for (a <- 0 until participants.size - 1) {
      val pair = (participants(a), participants(a + 1))
      pairs = pairs :+ pair
    }
    pairs :+ (participants.last, participants.head)
  }

  def reverse(entries: Seq[EntryRequest]): Seq[EntryRequest] = {
    val grouped = entries
      .flatMap(t =>
        Seq(
          t, {
            val txnId = t.operation match {
              case Operation.Simple(value)    => value.entryId
              case Operation.Authorize(value) => value.entryId
              case Operation.Capture(value)   => value.entryId
              case _                          => throw new IllegalArgumentException()
            }
            EntryRequest(
              Operation.Reverse(
                Reverse(txnId)
              )
            )
          }
        )
      )
      .groupBy(r =>
        r.operation match {
          case Operation.Reverse(_) => "REVERSE"
          case _                    => "NON"
        }
      )
    grouped("NON") ++ grouped("REVERSE")
  }

  def generate(): Seq[EntryRequest]

  def count(): Int
}
