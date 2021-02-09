package io.openledger.setup

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.typed.{ActorRef, ActorSystem, _}
import akka.kafka.scaladsl.Consumer.DrainingControl
import akka.kafka.scaladsl.{Committer, Consumer}
import akka.kafka.{CommitterSettings, ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.RunnableGraph
import akka.stream.typed.scaladsl.ActorFlow
import akka.stream.{ActorAttributes, Materializer, Supervision, _}
import io.openledger.api.kafka.StreamConsumer._
import io.openledger.domain.transaction.Transaction.{apply => _}
import io.openledger.kafka_operations.TransactionRequest
import io.openledger.setup.KafkaConsumerSetup.KafkaConsumerSettings

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{DurationInt, FiniteDuration}

object KafkaConsumerSetup {

  def apply(settings: KafkaConsumerSettings, coordinatedShutdown: CoordinatedShutdown, consumerActor: ActorRef[StreamOp])(implicit system: ActorSystem[_], executionContext: ExecutionContext, materializer: Materializer, scheduler: Scheduler) = new KafkaConsumerSetup(settings, coordinatedShutdown, consumerActor)

  case class KafkaConsumerSettings(processingTimeout: FiniteDuration, messagePerSecond: Int, topics: Set[String], kafkaSourceSettings: ConsumerSettings[String, Array[Byte]], kafkaComitterSettings: CommitterSettings)

}

class KafkaConsumerSetup(settings: KafkaConsumerSettings, coordinatedShutdown: CoordinatedShutdown, consumerActor: ActorRef[StreamOp])(implicit system: ActorSystem[_], executionContext: ExecutionContext, materializer: Materializer, scheduler: Scheduler) {

  private val resumeOnParsingException: Attributes = ActorAttributes.supervisionStrategy {
    case _: com.google.protobuf.InvalidProtocolBufferException => Supervision.Resume
    case _ => Supervision.Stop
  }


  private val consumerFlow: RunnableGraph[DrainingControl[Done]] = Consumer.committableSource(settings.kafkaSourceSettings, Subscriptions.topics(settings.topics))
    .throttle(settings.messagePerSecond, 1.second)
    .map(consumerRecord => StreamMessage(TransactionRequest.parseFrom(consumerRecord.record.value()).operation, consumerRecord.committableOffset))
    .withAttributes(resumeOnParsingException)
    .via(ActorFlow.ask(consumerActor)((wrapper, replyTo) => Receive(wrapper, replyTo))(settings.processingTimeout))
    .toMat(Committer.sink(settings.kafkaComitterSettings))(DrainingControl.apply)


  def run(): Unit = {
    val control: DrainingControl[Done] = consumerFlow.run()
    coordinatedShutdown.addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "shutdown-incoming-kafka") { () =>
      control.drainAndShutdown()
    }
  }
}
