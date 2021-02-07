package io.openledger

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.{ActorRef, ActorSystem, _}
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.{Keep, RunnableGraph}
import akka.stream.typed.scaladsl.ActorSink
import akka.stream.{ActorAttributes, Materializer, Supervision, _}
import akka.util.Timeout
import com.typesafe.config.Config
import io.openledger.StreamConsumer._
import io.openledger.domain.transaction.Transaction.{apply => _}
import io.openledger.kafka_operations.TransactionRequest
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, StringDeserializer}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

object KafkaConsumerSetup {
  def apply(coordinatedShutdown: CoordinatedShutdown, consumerActor: ActorRef[StreamIncoming])(implicit system: ActorSystem[_], executionContext: ExecutionContext, materializer: Materializer, scheduler: Scheduler) = new KafkaConsumerSetup(coordinatedShutdown, consumerActor)
}

class KafkaConsumerSetup(coordinatedShutdown: CoordinatedShutdown, consumerActor: ActorRef[StreamIncoming])(implicit system: ActorSystem[_], executionContext: ExecutionContext, materializer: Materializer, scheduler: Scheduler) {
  implicit val shutdownTimeout: Timeout = 10.seconds

  private val resumeOnParsingException: Attributes = ActorAttributes.supervisionStrategy {
    case _: com.google.protobuf.InvalidProtocolBufferException => Supervision.Resume
    case _ => Supervision.Stop
  }
  private val consumerConfig: Config = system.settings.config.getConfig("akka.kafka.consumer")
  private val consumerSettings: ConsumerSettings[String, Array[Byte]] = ConsumerSettings(
    config = consumerConfig, //TODO kafka configuration
    keyDeserializer = new StringDeserializer,
    valueDeserializer = new ByteArrayDeserializer)

  private val consumerFlow: RunnableGraph[Consumer.Control] = Consumer.plainSource(consumerSettings, Subscriptions.topics("")) //TODO topics
    .map(consumerRecord => TransactionRequest.parseFrom(consumerRecord.value()).operation)
    .withAttributes(resumeOnParsingException)
    .toMat(ActorSink.actorRefWithBackpressure(
      ref = consumerActor,
      onInitMessage = replyTo => StreamInitialized(replyTo),
      ackMessage = StreamAck,
      onCompleteMessage = StreamCompleted,
      onFailureMessage = ex => StreamFailure(ex),
      messageAdapter = (replyTo: ActorRef[StreamAck], message: TransactionRequest.Operation) => Op(message, replyTo)
    ))(Keep.left)


  def run(): Unit = {
    val control: Consumer.Control = consumerFlow.run()
    coordinatedShutdown.addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "shutdown-incoming-kafka") { () =>
      for (
        _ <- control.stop();
        _ <- consumerActor.ask(PrepareForShutdown);
        _ <- control.shutdown()
      ) yield Done
    }
  }
}
