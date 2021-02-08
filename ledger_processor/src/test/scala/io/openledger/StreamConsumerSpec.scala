package io.openledger

import akka.Done
import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.actor.typed.{ActorRef, Scheduler}
import akka.kafka.ConsumerMessage.Committable
import io.openledger.StreamConsumer._
import io.openledger.domain.transaction.Transaction
import io.openledger.domain.transaction.Transaction.{apply => _, _}
import io.openledger.kafka_operations.TransactionRequest.Operation
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.CompletionStage
import scala.compat.java8.FutureConverters.FutureOps
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.language.postfixOps

class StreamConsumerSpec extends ScalaTestWithActorTestKit
  with AnyWordSpecLike
  with BeforeAndAfterEach
  with LogCapturing
  with MockFactory {

  private val testProbe = testKit.createTestProbe[Committable]
  private val transactionProbe = testKit.createTestProbe[TransactionCommand]
  private val stubResultMessenger = mockFunction[TransactionResult, Unit]
  private val offset = StubCommittable()
  private val stubTransactionResolver = mockFunction[String, ActorRef[TransactionCommand]]
  private implicit val scheduler: Scheduler = testKit.system.scheduler
  private implicit val executionContext: ExecutionContextExecutor = testKit.system.executionContext

  "StreamConsumer" when {
    "tested in isolation" must {
      "Ack on NoOp" in {
        stubTransactionResolver expects * never

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! Receive(StreamMessage(Operation.Empty, offset), testProbe.ref)
        transactionProbe.expectNoMessage(1.second)

        testProbe.expectMessageType[StubCommittable]
      }
      "Forward to probe on Op-Resume" in {
        stubTransactionResolver expects "txn" returning transactionProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! Receive(StreamMessage(Operation.Resume(kafka_operations.Resume("txn")), offset), testProbe.ref)
        transactionProbe.expectMessageType[Transaction.Resume].replyTo ! Ack

        testProbe.expectMessageType[StubCommittable]
      }
      "Forward to probe on Op-Reverse" in {
        stubTransactionResolver expects "txn" returning transactionProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! Receive(StreamMessage(Operation.Reverse(kafka_operations.Reverse("txn")), offset), testProbe.ref)
        transactionProbe.expectMessageType[Transaction.Reverse].replyTo ! Ack

        testProbe.expectMessageType[StubCommittable]
      }
      "Forward to probe on Op-Simple" in {
        stubTransactionResolver expects "txn" returning transactionProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! Receive(StreamMessage(Operation.Simple(kafka_operations.Simple("ec", "txn", "d", "c", 1.5)), offset), testProbe.ref)
        val sniffed = transactionProbe.expectMessageType[Transaction.Begin]
        sniffed.authOnly shouldBe false
        sniffed.entryCode shouldBe "ec"
        sniffed.accountToCredit shouldBe "c"
        sniffed.accountToDebit shouldBe "d"
        sniffed.amount shouldBe 1.5
        sniffed.replyTo ! Ack


        testProbe.expectMessageType[StubCommittable]
      }
      "Forward to probe on Op-Authorize" in {
        stubTransactionResolver expects "txn" returning transactionProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! Receive(StreamMessage(Operation.Authorize(kafka_operations.Authorize("ec", "txn", "d", "c", 1.5)), offset), testProbe.ref)
        val sniffed = transactionProbe.expectMessageType[Transaction.Begin]
        sniffed.authOnly shouldBe true
        sniffed.entryCode shouldBe "ec"
        sniffed.accountToCredit shouldBe "c"
        sniffed.accountToDebit shouldBe "d"
        sniffed.amount shouldBe 1.5
        sniffed.replyTo ! Ack


        testProbe.expectMessageType[StubCommittable]
      }
      "Forward to probe on Op-Capture" in {
        stubTransactionResolver expects "txn" returning transactionProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! Receive(StreamMessage(Operation.Capture(kafka_operations.Capture("txn", 1.5)), offset), testProbe.ref)
        val sniffed = transactionProbe.expectMessageType[Transaction.Capture]
        sniffed.captureAmount shouldBe 1.5
        sniffed.replyTo ! Ack


        testProbe.expectMessageType[StubCommittable]
      }

      "Forward to probe on Op-Resume - Nack" in {
        stubTransactionResolver expects "txn" returning transactionProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! Receive(StreamMessage(Operation.Resume(kafka_operations.Resume("txn")), offset), testProbe.ref)
        transactionProbe.expectMessageType[Transaction.Resume].replyTo ! Nack

        testProbe.expectMessageType[StubCommittable]
      }
      "Forward to probe on Op-Reverse - Nack" in {
        stubTransactionResolver expects "txn" returning transactionProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! Receive(StreamMessage(Operation.Reverse(kafka_operations.Reverse("txn")), offset), testProbe.ref)
        transactionProbe.expectMessageType[Transaction.Reverse].replyTo ! Nack

        testProbe.expectMessageType[StubCommittable]
      }
      "Forward to probe on Op-Simple - Nack" in {
        stubTransactionResolver expects "txn" returning transactionProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! Receive(StreamMessage(Operation.Simple(kafka_operations.Simple("ec", "txn", "d", "c", 1.5)), offset), testProbe.ref)
        val sniffed = transactionProbe.expectMessageType[Transaction.Begin]
        sniffed.authOnly shouldBe false
        sniffed.entryCode shouldBe "ec"
        sniffed.accountToCredit shouldBe "c"
        sniffed.accountToDebit shouldBe "d"
        sniffed.amount shouldBe 1.5
        sniffed.replyTo ! Nack


        testProbe.expectMessageType[StubCommittable]
      }
      "Forward to probe on Op-Authorize - Nack" in {
        stubTransactionResolver expects "txn" returning transactionProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! Receive(StreamMessage(Operation.Authorize(kafka_operations.Authorize("ec", "txn", "d", "c", 1.5)), offset), testProbe.ref)
        val sniffed = transactionProbe.expectMessageType[Transaction.Begin]
        sniffed.authOnly shouldBe true
        sniffed.entryCode shouldBe "ec"
        sniffed.accountToCredit shouldBe "c"
        sniffed.accountToDebit shouldBe "d"
        sniffed.amount shouldBe 1.5
        sniffed.replyTo ! Nack


        testProbe.expectMessageType[StubCommittable]
      }
      "Forward to probe on Op-Capture - Nack" in {
        stubTransactionResolver expects "txn" returning transactionProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! Receive(StreamMessage(Operation.Capture(kafka_operations.Capture("txn", 1.5)), offset), testProbe.ref)
        val sniffed = transactionProbe.expectMessageType[Transaction.Capture]
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
