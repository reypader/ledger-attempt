package io.openledger

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.typed.{ActorRef, ActorSystem, _}
import akka.kafka.scaladsl.Consumer.DrainingControl
import akka.kafka.scaladsl.{Committer, Consumer}
import akka.kafka.{CommitterSettings, ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.RunnableGraph
import akka.stream.typed.scaladsl.ActorFlow
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
  def apply(coordinatedShutdown: CoordinatedShutdown, consumerActor: ActorRef[StreamOp])(implicit system: ActorSystem[_], executionContext: ExecutionContext, materializer: Materializer, scheduler: Scheduler) = new KafkaConsumerSetup(coordinatedShutdown, consumerActor)
}

class KafkaConsumerSetup(coordinatedShutdown: CoordinatedShutdown, consumerActor: ActorRef[StreamOp])(implicit system: ActorSystem[_], executionContext: ExecutionContext, materializer: Materializer, scheduler: Scheduler) {
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

  private val committerConfig: Config = system.settings.config.getConfig("akka.kafka.committer")
  private val committerSettings: CommitterSettings = CommitterSettings(
    config = committerConfig)


  private val consumerFlow: RunnableGraph[DrainingControl[Done]] = Consumer.committableSource(consumerSettings, Subscriptions.topics("")) //TODO topics
    .map(consumerRecord => StreamMessage(TransactionRequest.parseFrom(consumerRecord.record.value()).operation, consumerRecord.committableOffset))
    .withAttributes(resumeOnParsingException)
    .via(ActorFlow.ask(consumerActor)((wrapper, replyTo) => Receive(wrapper, replyTo)))
    .toMat(Committer.sink(committerSettings))(DrainingControl.apply)


  def run(): Unit = {
    val control: DrainingControl[Done] = consumerFlow.run()
    coordinatedShutdown.addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "shutdown-incoming-kafka") { () =>
      control.drainAndShutdown()
    }
  }
}
