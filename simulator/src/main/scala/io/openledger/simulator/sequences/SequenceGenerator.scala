package io.openledger.simulator.sequences

import akka.actor.typed.{ActorRef, Behavior}
import io.openledger.kafka_operations.EntryRequest.Operation
import io.openledger.kafka_operations.EntryRequest.Operation
import io.openledger.kafka_operations._
import io.openledger.simulator.Monitor.MonitorOperation
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

  def generate(monitor: ActorRef[MonitorOperation]): Seq[Behavior[EntryResult]]

}
