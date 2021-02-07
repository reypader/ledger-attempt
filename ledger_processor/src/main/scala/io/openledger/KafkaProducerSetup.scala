package io.openledger

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.typed.ActorSystem
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.Producer
import akka.stream.scaladsl.{Keep, RunnableGraph, Source, SourceQueue, SourceQueueWithComplete}
import akka.stream.{Materializer, OverflowStrategy}
import io.openledger.domain.transaction.Transaction._
import io.openledger.operations.TransactionResult.Balance
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{ByteArraySerializer, StringSerializer}

import scala.concurrent.{ExecutionContext, Future}

object KafkaProducerSetup {
  def apply(system: ActorSystem[_], coordinatedShutdown: CoordinatedShutdown)(implicit executionContext: ExecutionContext, materializer: Materializer) = new KafkaProducerSetup(system, coordinatedShutdown)
}

class KafkaProducerSetup(system: ActorSystem[_], coordinatedShutdown: CoordinatedShutdown)(implicit executionContext: ExecutionContext, materializer: Materializer) {
  private val config = system.settings.config.getConfig("akka.kafka.producer")
  private val producerSettings =
    ProducerSettings(config, new StringSerializer, new ByteArraySerializer)
      .withBootstrapServers("") // TODO bootstrapServers
  private val outboundFlow: RunnableGraph[(SourceQueueWithComplete[TransactionResult], Future[Done])] =
    Source
      .queue[TransactionResult](bufferSize = 100, overflowStrategy = OverflowStrategy.backpressure, maxConcurrentOffers = 10) //TODO : Config these
      .map {
        case m@TransactionSuccessful(transactionId, debitedAccountResultingBalance, creditedAccountResultingBalance) =>
          operations.TransactionResult(transactionId, m.status, m.code, Some(Balance(debitedAccountResultingBalance.availableBalance.doubleValue, debitedAccountResultingBalance.currentBalance.doubleValue)), Some(Balance(creditedAccountResultingBalance.availableBalance.doubleValue, creditedAccountResultingBalance.currentBalance.doubleValue)))
        case m@TransactionFailed(transactionId, _) =>
          operations.TransactionResult(transactionId, m.status, m.code, None, None)
        case m@TransactionReversed(transactionId, debitedAccountResultingBalance, option) =>
          option match {
            case Some(creditedAccountResultingBalance) =>
              operations.TransactionResult(transactionId, m.status, m.code, Some(Balance(debitedAccountResultingBalance.availableBalance.doubleValue, debitedAccountResultingBalance.currentBalance.doubleValue)), Some(Balance(creditedAccountResultingBalance.availableBalance.doubleValue, creditedAccountResultingBalance.currentBalance.doubleValue)))
            case None =>
              operations.TransactionResult(transactionId, m.status, m.code, Some(Balance(debitedAccountResultingBalance.availableBalance.doubleValue, debitedAccountResultingBalance.currentBalance.doubleValue)), None)
          }
        case m@TransactionPending(transactionId, debitedAccountResultingBalance) =>
          operations.TransactionResult(transactionId, m.status, m.code, Some(Balance(debitedAccountResultingBalance.availableBalance.doubleValue, debitedAccountResultingBalance.currentBalance.doubleValue)), None)
        case m@CaptureRejected(transactionId, _) =>
          operations.TransactionResult(transactionId, m.status, m.code, None, None)
      }
      .map(result => new ProducerRecord("", result.transactionId, result.toByteArray)) //TODO config
      .toMat(Producer.plainSink(producerSettings))(Keep.both)

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
