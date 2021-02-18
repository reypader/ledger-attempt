package io.openledger.simulator.sequences

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.stream.scaladsl.SourceQueue
import io.openledger.kafka_operations.EntryRequest.Operation
import io.openledger.kafka_operations._
import io.openledger.simulator.Monitor.{Done, MonitorOperation}

import java.time.Instant
import java.util.UUID
import scala.util.Random

case class TransferReverse(
    participants: Seq[String],
    queue: SourceQueue[((String, ActorRef[EntryResult]), EntryRequest)],
    iterations: Int
) extends SequenceGenerator {
  def pairs: Seq[(String, String)] = createPairs(Random.shuffle(participants))

  override def generate(monitor: ActorRef[MonitorOperation]): Seq[Behavior[EntryResult]] = {
    pairs
      .map(pair => {
        val txnId = UUID.randomUUID().toString
        simpleBehavior(
          txnId,
          Seq(
            EntryRequest(
              Operation.Simple(
                Simple(
                  entryCode = "AUTH_CAP",
                  entryId = txnId,
                  accountToDebit = pair._1,
                  accountToCredit = pair._2,
                  amount = 3
                )
              )
            ),
            EntryRequest(
              Operation.Reverse(
                Reverse(entryId = txnId)
              )
            )
          ),
          queue,
          iterations,
          1,
          monitor
        )
      })
  }

  def simpleBehavior(
      entryId: String,
      sequence: Seq[EntryRequest],
      queue: SourceQueue[((String, ActorRef[EntryResult]), EntryRequest)],
      maxIteration: Int,
      currentIteration: Int = 0,
      monitor: ActorRef[MonitorOperation]
  ): Behavior[EntryResult] = Behaviors.setup { context =>
    queue.offer(((entryId, context.self), sequence(0)))
    val mark = Instant.now().toEpochMilli
    Behaviors.receiveMessage {
      case EntryResult(_, status, _, _, _, _) if status == "SUCCESS" =>
        monitor ! Done("SIMPLE", Instant.now().toEpochMilli - mark)
        reverseBehavior(entryId, sequence, queue, maxIteration, currentIteration, monitor)
      case EntryResult(entryId, status, code, _, _, _) =>
        context.log.error(s"$entryId encountered problem : $status, $code")
        Behaviors.stopped
    }
  }

  def reverseBehavior(
      entryId: String,
      sequence: Seq[EntryRequest],
      queue: SourceQueue[((String, ActorRef[EntryResult]), EntryRequest)],
      maxIteration: Int,
      currentIteration: Int = 0,
      monitor: ActorRef[MonitorOperation]
  ): Behavior[EntryResult] = Behaviors.setup { context =>
    queue.offer(((entryId, context.self), sequence(1)))
    val mark = Instant.now().toEpochMilli
    Behaviors.receiveMessage {
      case EntryResult(_, status, _, _, _, _) if status == "REVERSED" && currentIteration < maxIteration =>
        monitor ! Done("REVERSE", Instant.now().toEpochMilli - mark)
        simpleBehavior(UUID.randomUUID().toString, sequence, queue, maxIteration, currentIteration + 1, monitor)
      case EntryResult(_, status, _, _, _, _) if status == "REVERSED" && currentIteration >= maxIteration =>
        monitor ! Done("REVERSE", Instant.now().toEpochMilli - mark)
        Behaviors.stopped
      case EntryResult(entryId, status, code, _, _, _) =>
        context.log.error(s"$entryId encountered problem : $status, $code")
        Behaviors.stopped
    }
  }

  override def toString: String = s"$iterations iterations of (simple,reversal) between ${pairs.size} pairs"
}
