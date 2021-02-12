package io.openledger.api.kafka

import akka.Done
import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.actor.typed.{ActorRef, Scheduler}
import akka.kafka.ConsumerMessage.Committable
import io.openledger.api.kafka.StreamConsumer._
import io.openledger.domain.entry.Entry
import io.openledger.domain.entry.Entry.{apply => _, _}
import io.openledger.kafka_operations
import io.openledger.kafka_operations.EntryRequest.Operation
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.CompletionStage
import scala.compat.java8.FutureConverters.FutureOps
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.language.postfixOps

class StreamConsumerSpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike
    with BeforeAndAfterEach
    with LogCapturing
    with MockFactory {

  private val testProbe = testKit.createTestProbe[Committable]
  private val entryProbe = testKit.createTestProbe[EntryCommand]
  private val stubResultMessenger = mockFunction[EntryResult, Unit]
  private val offset = StubCommittable()
  private val stubEntryResolver = mockFunction[String, ActorRef[EntryCommand]]
  private implicit val scheduler: Scheduler = testKit.system.scheduler
  private implicit val executionContext: ExecutionContextExecutor = testKit.system.executionContext

  "StreamConsumer" when {
    "tested in isolation" must {
      "Ack on NoOp" in {
        stubEntryResolver expects * never

        val underTest = testKit.spawn(StreamConsumer(stubEntryResolver, stubResultMessenger))
        underTest ! Receive(StreamMessage(Operation.Empty, offset), testProbe.ref)
        entryProbe.expectNoMessage(1.second)

        testProbe.expectMessageType[StubCommittable]
      }
      "Forward to probe on Op-Reverse" in {
        stubEntryResolver expects "txn" returning entryProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubEntryResolver, stubResultMessenger))
        underTest ! Receive(StreamMessage(Operation.Reverse(kafka_operations.Reverse("txn")), offset), testProbe.ref)
        entryProbe.expectMessageType[Entry.Reverse].replyTo ! Ack

        testProbe.expectMessageType[StubCommittable]
      }
      "Forward to probe on Op-Simple" in {
        stubEntryResolver expects "txn" returning entryProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubEntryResolver, stubResultMessenger))
        underTest ! Receive(
          StreamMessage(Operation.Simple(kafka_operations.Simple("ec", "txn", "d", "c", 1.5)), offset),
          testProbe.ref
        )
        val sniffed = entryProbe.expectMessageType[Entry.Begin]
        sniffed.authOnly shouldBe false
        sniffed.entryCode shouldBe "ec"
        sniffed.accountToCredit shouldBe "c"
        sniffed.accountToDebit shouldBe "d"
        sniffed.amount shouldBe 1.5
        sniffed.replyTo ! Ack

        testProbe.expectMessageType[StubCommittable]
      }
      "Forward to probe on Op-Authorize" in {
        stubEntryResolver expects "txn" returning entryProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubEntryResolver, stubResultMessenger))
        underTest ! Receive(
          StreamMessage(Operation.Authorize(kafka_operations.Authorize("ec", "txn", "d", "c", 1.5)), offset),
          testProbe.ref
        )
        val sniffed = entryProbe.expectMessageType[Entry.Begin]
        sniffed.authOnly shouldBe true
        sniffed.entryCode shouldBe "ec"
        sniffed.accountToCredit shouldBe "c"
        sniffed.accountToDebit shouldBe "d"
        sniffed.amount shouldBe 1.5
        sniffed.replyTo ! Ack

        testProbe.expectMessageType[StubCommittable]
      }
      "Forward to probe on Op-Capture" in {
        stubEntryResolver expects "txn" returning entryProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubEntryResolver, stubResultMessenger))
        underTest ! Receive(
          StreamMessage(Operation.Capture(kafka_operations.Capture("txn", 1.5)), offset),
          testProbe.ref
        )
        val sniffed = entryProbe.expectMessageType[Entry.Capture]
        sniffed.captureAmount shouldBe 1.5
        sniffed.replyTo ! Ack

        testProbe.expectMessageType[StubCommittable]
      }

      "Forward to probe on Op-Reverse - Nack" in {
        stubEntryResolver expects "txn" returning entryProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubEntryResolver, stubResultMessenger))
        underTest ! Receive(StreamMessage(Operation.Reverse(kafka_operations.Reverse("txn")), offset), testProbe.ref)
        entryProbe.expectMessageType[Entry.Reverse].replyTo ! Nack

        testProbe.expectMessageType[StubCommittable]
      }
      "Forward to probe on Op-Simple - Nack" in {
        stubEntryResolver expects "txn" returning entryProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubEntryResolver, stubResultMessenger))
        underTest ! Receive(
          StreamMessage(Operation.Simple(kafka_operations.Simple("ec", "txn", "d", "c", 1.5)), offset),
          testProbe.ref
        )
        val sniffed = entryProbe.expectMessageType[Entry.Begin]
        sniffed.authOnly shouldBe false
        sniffed.entryCode shouldBe "ec"
        sniffed.accountToCredit shouldBe "c"
        sniffed.accountToDebit shouldBe "d"
        sniffed.amount shouldBe 1.5
        sniffed.replyTo ! Nack

        testProbe.expectMessageType[StubCommittable]
      }
      "Forward to probe on Op-Authorize - Nack" in {
        stubEntryResolver expects "txn" returning entryProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubEntryResolver, stubResultMessenger))
        underTest ! Receive(
          StreamMessage(Operation.Authorize(kafka_operations.Authorize("ec", "txn", "d", "c", 1.5)), offset),
          testProbe.ref
        )
        val sniffed = entryProbe.expectMessageType[Entry.Begin]
        sniffed.authOnly shouldBe true
        sniffed.entryCode shouldBe "ec"
        sniffed.accountToCredit shouldBe "c"
        sniffed.accountToDebit shouldBe "d"
        sniffed.amount shouldBe 1.5
        sniffed.replyTo ! Nack

        testProbe.expectMessageType[StubCommittable]
      }
      "Forward to probe on Op-Capture - Nack" in {
        stubEntryResolver expects "txn" returning entryProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubEntryResolver, stubResultMessenger))
        underTest ! Receive(
          StreamMessage(Operation.Capture(kafka_operations.Capture("txn", 1.5)), offset),
          testProbe.ref
        )
        val sniffed = entryProbe.expectMessageType[Entry.Capture]
        sniffed.captureAmount shouldBe 1.5
        sniffed.replyTo ! Nack

        testProbe.expectMessageType[StubCommittable]
      }

    }
  }

  private case class StubCommittable() extends Committable {
    override def commitInternal(): Future[Done] = Future(Done)

    override def commitScaladsl(): Future[Done] = Future(Done)

    override def commitJavadsl(): CompletionStage[Done] = Future(Done.done()).toJava

    override def batchSize: Long = 1
  }

}
