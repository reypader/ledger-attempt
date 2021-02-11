package io.openledger.simulator.sequences

import io.openledger.kafka_operations.TransactionRequest.Operation
import io.openledger.kafka_operations.{Reverse, TransactionRequest}
import org.slf4j.LoggerFactory

trait SequenceGenerator {

  def createPairs(participants: Seq[String]): Seq[(String, String)] = {
    var pairs = Seq[(String, String)]()
    for (a <- 0 until participants.size - 1) {
      val pair = (participants(a), participants(a+1))
      pairs = pairs :+ pair
    }
    pairs :+ (participants.last, participants.head)
  }

  def reverse(transactions: Seq[TransactionRequest]): Seq[TransactionRequest] = {
    val grouped = transactions.flatMap(t =>
      Seq(
        t,
        {
          val txnId = t.operation match {
            case Operation.Simple(value) => value.transactionId
            case Operation.Authorize(value) => value.transactionId
            case Operation.Capture(value) => value.transactionId
            case _ => throw new IllegalArgumentException()
          }
          TransactionRequest(
            Operation.Reverse(
              Reverse(txnId)
            )
          )
        }
      )
    ).groupBy(r => r.operation match {
      case Operation.Reverse(_) => "REVERSE"
      case _ => "NON"
    })
    grouped("NON") ++ grouped("REVERSE")
  }

  def generate(): Seq[TransactionRequest]

  def count():Int
}
