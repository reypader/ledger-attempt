package io.openledger

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.typed.ActorSystem
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.Producer
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Keep, RunnableGraph, Source, SourceQueue, SourceQueueWithComplete}
import io.openledger.KafkaProducerSetup.KafkaProducerSettings
import io.openledger.domain.transaction.Transaction._
import io.openledger.kafka_operations.TransactionResult.Balance
import org.apache.kafka.clients.producer.ProducerRecord

import scala.concurrent.{ExecutionContext, Future}

object KafkaProducerSetup {

  case class KafkaProducerSettings(topic: String, bufferSize: Int, kafkaProducerSettings: ProducerSettings[String, Array[Byte]])

  def apply(settings: KafkaProducerSettings, coordinatedShutdown: CoordinatedShutdown)(implicit system: ActorSystem[_], executionContext: ExecutionContext) = new KafkaProducerSetup(settings, coordinatedShutdown)
}

class KafkaProducerSetup(settings: KafkaProducerSettings, coordinatedShutdown: CoordinatedShutdown)(implicit system: ActorSystem[_], executionContext: ExecutionContext) {

  private val outboundFlow: RunnableGraph[(SourceQueueWithComplete[TransactionResult], Future[Done])] =
    Source
      .queue[TransactionResult](bufferSize = settings.bufferSize, overflowStrategy = OverflowStrategy.backpressure, maxConcurrentOffers = math.max(settings.bufferSize / 10, 1))
      .map {
        case m@AdjustmentSuccessful(transactionId, mode, adjustedBalance) =>
          mode match {
            case AccountingMode.DEBIT =>
              kafka_operations.TransactionResult(transactionId, m.status, m.code, Some(Balance(adjustedBalance.availableBalance.doubleValue, adjustedBalance.currentBalance.doubleValue)), None)
            case AccountingMode.CREDIT =>
              kafka_operations.TransactionResult(transactionId, m.status, m.code, None, Some(Balance(adjustedBalance.availableBalance.doubleValue, adjustedBalance.currentBalance.doubleValue)))
          }
        case m@TransactionSuccessful(transactionId, debitedAccountResultingBalance, creditedAccountResultingBalance) =>
          kafka_operations.TransactionResult(transactionId, m.status, m.code, Some(Balance(debitedAccountResultingBalance.availableBalance.doubleValue, debitedAccountResultingBalance.currentBalance.doubleValue)), Some(Balance(creditedAccountResultingBalance.availableBalance.doubleValue, creditedAccountResultingBalance.currentBalance.doubleValue)))
        case m@TransactionFailed(transactionId, _) =>
          kafka_operations.TransactionResult(transactionId, m.status, m.code, None, None)
        case m@CommandRejected(transactionId, _) =>
          kafka_operations.TransactionResult(transactionId, m.status, m.code, None, None)
        case m@TransactionReversed(transactionId, debitedAccountResultingBalance, option) =>
          option match {
            case Some(creditedAccountResultingBalance) =>
              kafka_operations.TransactionResult(transactionId, m.status, m.code, Some(Balance(debitedAccountResultingBalance.availableBalance.doubleValue, debitedAccountResultingBalance.currentBalance.doubleValue)), Some(Balance(creditedAccountResultingBalance.availableBalance.doubleValue, creditedAccountResultingBalance.currentBalance.doubleValue)))
            case None =>
              kafka_operations.TransactionResult(transactionId, m.status, m.code, Some(Balance(debitedAccountResultingBalance.availableBalance.doubleValue, debitedAccountResultingBalance.currentBalance.doubleValue)), None)
          }
        case m@TransactionPending(transactionId, debitedAccountResultingBalance) =>
          kafka_operations.TransactionResult(transactionId, m.status, m.code, Some(Balance(debitedAccountResultingBalance.availableBalance.doubleValue, debitedAccountResultingBalance.currentBalance.doubleValue)), None)
        case m@CaptureRejected(transactionId, _) =>
          kafka_operations.TransactionResult(transactionId, m.status, m.code, None, None)
      }
      .map(result => new ProducerRecord(settings.topic, result.transactionId, result.toByteArray))
      .toMat(Producer.plainSink(settings.kafkaProducerSettings))(Keep.both)

  def run(): SourceQueue[TransactionResult] = {
    val (producerQueue, producerCompletion): (SourceQueueWithComplete[TransactionResult], Future[Done]) = outboundFlow.run()
    coordinatedShutdown.addTask(CoordinatedShutdown.PhaseBeforeActorSystemTerminate, "shutdown-outgoing-kafka") { () => {
      producerQueue.complete()
      for (
        _ <- producerQueue.watchCompletion();
        _ <- producerCompletion
      ) yield Done
    }
    }
    producerQueue
  }
}
