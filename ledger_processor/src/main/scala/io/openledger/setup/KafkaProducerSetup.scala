package io.openledger.setup

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.typed.ActorSystem
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.Producer
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Keep, RunnableGraph, Source, SourceQueue, SourceQueueWithComplete}
import io.openledger.domain.entry.Entry._
import io.openledger.kafka_operations.EntryResult.Balance
import io.openledger.setup.KafkaProducerSetup.KafkaProducerSettings
import io.openledger.{AccountingMode, kafka_operations}
import org.apache.kafka.clients.producer.ProducerRecord

import scala.concurrent.{ExecutionContext, Future}

object KafkaProducerSetup {

  def apply(settings: KafkaProducerSettings, coordinatedShutdown: CoordinatedShutdown)(implicit
      system: ActorSystem[_],
      executionContext: ExecutionContext
  ) = new KafkaProducerSetup(settings, coordinatedShutdown)

  case class KafkaProducerSettings(
      topic: String,
      bufferSize: Int,
      kafkaProducerSettings: ProducerSettings[String, Array[Byte]]
  )

}

class KafkaProducerSetup(settings: KafkaProducerSettings, coordinatedShutdown: CoordinatedShutdown)(implicit
    system: ActorSystem[_],
    executionContext: ExecutionContext
) {

  private val outboundFlow: RunnableGraph[(SourceQueueWithComplete[EntryResult], Future[Done])] =
    Source
      .queue[EntryResult](
        bufferSize = settings.bufferSize,
        overflowStrategy = OverflowStrategy.backpressure,
        maxConcurrentOffers = math.max(settings.bufferSize / 10, 1)
      )
      .map {
        case m @ AdjustmentSuccessful(entryId, mode, adjustedBalance) =>
          mode match {
            case AccountingMode.DEBIT =>
              kafka_operations.EntryResult(
                entryId,
                m.status,
                m.code,
                Some(Balance(adjustedBalance.availableBalance.doubleValue, adjustedBalance.currentBalance.doubleValue)),
                None
              )
            case AccountingMode.CREDIT =>
              kafka_operations.EntryResult(
                entryId,
                m.status,
                m.code,
                None,
                Some(Balance(adjustedBalance.availableBalance.doubleValue, adjustedBalance.currentBalance.doubleValue))
              )
          }
        case m @ EntrySuccessful(entryId, debitedAccountResultingBalance, creditedAccountResultingBalance) =>
          kafka_operations.EntryResult(
            entryId,
            m.status,
            m.code,
            Some(
              Balance(
                debitedAccountResultingBalance.availableBalance.doubleValue,
                debitedAccountResultingBalance.currentBalance.doubleValue
              )
            ),
            Some(
              Balance(
                creditedAccountResultingBalance.availableBalance.doubleValue,
                creditedAccountResultingBalance.currentBalance.doubleValue
              )
            )
          )
        case m @ EntryFailed(entryId, _) =>
          kafka_operations.EntryResult(entryId, m.status, m.code, None, None)
        case m @ CommandRejected(entryId, _) =>
          kafka_operations.EntryResult(entryId, m.status, m.code, None, None)
        case m @ EntryReversed(entryId, debitedAccountResultingBalance, option) =>
          option match {
            case Some(creditedAccountResultingBalance) =>
              kafka_operations.EntryResult(
                entryId,
                m.status,
                m.code,
                Some(
                  Balance(
                    debitedAccountResultingBalance.availableBalance.doubleValue,
                    debitedAccountResultingBalance.currentBalance.doubleValue
                  )
                ),
                Some(
                  Balance(
                    creditedAccountResultingBalance.availableBalance.doubleValue,
                    creditedAccountResultingBalance.currentBalance.doubleValue
                  )
                )
              )
            case None =>
              kafka_operations.EntryResult(
                entryId,
                m.status,
                m.code,
                Some(
                  Balance(
                    debitedAccountResultingBalance.availableBalance.doubleValue,
                    debitedAccountResultingBalance.currentBalance.doubleValue
                  )
                ),
                None
              )
          }
        case m @ EntryPending(entryId, debitedAccountResultingBalance) =>
          kafka_operations.EntryResult(
            entryId,
            m.status,
            m.code,
            Some(
              Balance(
                debitedAccountResultingBalance.availableBalance.doubleValue,
                debitedAccountResultingBalance.currentBalance.doubleValue
              )
            ),
            None
          )
        case m @ CaptureRejected(entryId, _) =>
          kafka_operations.EntryResult(entryId, m.status, m.code, None, None)

        case m @ CommandThrottled(entryId) =>
          kafka_operations.EntryResult(entryId, m.status, m.code, None, None)
      }
      .map(result => new ProducerRecord(settings.topic, result.entryId, result.toByteArray))
      .toMat(Producer.plainSink(settings.kafkaProducerSettings))(Keep.both)

  def run(): SourceQueue[EntryResult] = {
    val (producerQueue, producerCompletion): (SourceQueueWithComplete[EntryResult], Future[Done]) = outboundFlow.run()
    coordinatedShutdown.addTask(CoordinatedShutdown.PhaseBeforeActorSystemTerminate, "shutdown-outgoing-kafka") { () =>
      {
        producerQueue.complete()
        for {
          _ <- producerQueue.watchCompletion()
          _ <- producerCompletion
        } yield Done
      }
    }
    producerQueue
  }
}
