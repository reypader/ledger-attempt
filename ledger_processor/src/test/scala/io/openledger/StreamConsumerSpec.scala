package io.openledger

import akka.Done
import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.actor.typed.ActorRef
import akka.stream.scaladsl.Source
import akka.stream.typed.scaladsl.ActorSink
import io.openledger.StreamConsumer._
import io.openledger.domain.transaction.Transaction
import io.openledger.domain.transaction.Transaction.{apply => _, _}
import io.openledger.kafka_operations.TransactionRequest.Operation
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class StreamConsumerSpec extends ScalaTestWithActorTestKit
  with AnyWordSpecLike
  with BeforeAndAfterEach
  with LogCapturing
  with MockFactory {

  private val shutdownProbe = testKit.createTestProbe[Done]
  private val testProbe = testKit.createTestProbe[StreamAck]
  private val transactionProbe = testKit.createTestProbe[TransactionCommand]
  private val stubResultMessenger = mockFunction[TransactionResult, Unit]

  private val stubTransactionResolver = mockFunction[String, ActorRef[TransactionCommand]]


  "StreamConsumer" when {
    "tested in isolation" must {
      "Ack on StreamInitialized" in {
        stubTransactionResolver expects (*) never

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! StreamInitialized(testProbe.ref)
        transactionProbe.expectNoMessage(1.second)
        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectMessageType[StreamAck]
      }
      "Ack on NoOp" in {
        stubTransactionResolver expects (*) never

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! Op(Operation.Empty, testProbe.ref)
        transactionProbe.expectNoMessage(1.second)
        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectMessageType[StreamAck]
      }
      "Forward to probe on Op-Resume" in {
        stubTransactionResolver expects "txn" returning transactionProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! Op(Operation.Resume(kafka_operations.Resume("txn")), testProbe.ref)
        transactionProbe.expectMessageType[Transaction.Resume].replyTo ! Ack
        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectMessageType[StreamAck]
      }
      "Forward to probe on Op-Reverse" in {
        stubTransactionResolver expects "txn" returning transactionProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! Op(Operation.Reverse(kafka_operations.Reverse("txn")), testProbe.ref)
        transactionProbe.expectMessageType[Transaction.Reverse].replyTo ! Ack
        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectMessageType[StreamAck]
      }
      "Forward to probe on Op-Simple" in {
        stubTransactionResolver expects "txn" returning transactionProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! Op(Operation.Simple(kafka_operations.Simple("ec", "txn", "d", "c", 1.5)), testProbe.ref)
        val sniffed = transactionProbe.expectMessageType[Transaction.Begin]
        sniffed.authOnly shouldBe false
        sniffed.entryCode shouldBe "ec"
        sniffed.accountToCredit shouldBe "c"
        sniffed.accountToDebit shouldBe "d"
        sniffed.amount shouldBe 1.5
        sniffed.replyTo ! Ack

        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectMessageType[StreamAck]
      }
      "Forward to probe on Op-Authorize" in {
        stubTransactionResolver expects "txn" returning transactionProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! Op(Operation.Authorize(kafka_operations.Authorize("ec", "txn", "d", "c", 1.5)), testProbe.ref)
        val sniffed = transactionProbe.expectMessageType[Transaction.Begin]
        sniffed.authOnly shouldBe true
        sniffed.entryCode shouldBe "ec"
        sniffed.accountToCredit shouldBe "c"
        sniffed.accountToDebit shouldBe "d"
        sniffed.amount shouldBe 1.5
        sniffed.replyTo ! Ack

        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectMessageType[StreamAck]
      }
      "Forward to probe on Op-Capture" in {
        stubTransactionResolver expects "txn" returning transactionProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! Op(Operation.Capture(kafka_operations.Capture("txn", 1.5)), testProbe.ref)
        val sniffed = transactionProbe.expectMessageType[Transaction.Capture]
        sniffed.captureAmount shouldBe 1.5
        sniffed.replyTo ! Ack

        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectMessageType[StreamAck]
      }

      "Forward to probe on Op-Resume - Nack" in {
        stubTransactionResolver expects "txn" returning transactionProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! Op(Operation.Resume(kafka_operations.Resume("txn")), testProbe.ref)
        transactionProbe.expectMessageType[Transaction.Resume].replyTo ! Nack
        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectMessageType[StreamAck]
      }
      "Forward to probe on Op-Reverse - Nack" in {
        stubTransactionResolver expects "txn" returning transactionProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! Op(Operation.Reverse(kafka_operations.Reverse("txn")), testProbe.ref)
        transactionProbe.expectMessageType[Transaction.Reverse].replyTo ! Nack
        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectMessageType[StreamAck]
      }
      "Forward to probe on Op-Simple - Nack" in {
        stubTransactionResolver expects "txn" returning transactionProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! Op(Operation.Simple(kafka_operations.Simple("ec", "txn", "d", "c", 1.5)), testProbe.ref)
        val sniffed = transactionProbe.expectMessageType[Transaction.Begin]
        sniffed.authOnly shouldBe false
        sniffed.entryCode shouldBe "ec"
        sniffed.accountToCredit shouldBe "c"
        sniffed.accountToDebit shouldBe "d"
        sniffed.amount shouldBe 1.5
        sniffed.replyTo ! Nack

        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectMessageType[StreamAck]
      }
      "Forward to probe on Op-Authorize - Nack" in {
        stubTransactionResolver expects "txn" returning transactionProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! Op(Operation.Authorize(kafka_operations.Authorize("ec", "txn", "d", "c", 1.5)), testProbe.ref)
        val sniffed = transactionProbe.expectMessageType[Transaction.Begin]
        sniffed.authOnly shouldBe true
        sniffed.entryCode shouldBe "ec"
        sniffed.accountToCredit shouldBe "c"
        sniffed.accountToDebit shouldBe "d"
        sniffed.amount shouldBe 1.5
        sniffed.replyTo ! Nack

        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectMessageType[StreamAck]
      }
      "Forward to probe on Op-Capture - Nack" in {
        stubTransactionResolver expects "txn" returning transactionProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! Op(Operation.Capture(kafka_operations.Capture("txn", 1.5)), testProbe.ref)
        val sniffed = transactionProbe.expectMessageType[Transaction.Capture]
        sniffed.captureAmount shouldBe 1.5
        sniffed.replyTo ! Nack

        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectMessageType[StreamAck]
      }

      "Stash message on PrepareForShutdown" in {
        stubTransactionResolver expects "txn" returning transactionProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! PrepareForShutdown(shutdownProbe.ref)
        transactionProbe.expectNoMessage(1.second)
        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectNoMessage(1.second)

        underTest ! Op(Operation.Resume(kafka_operations.Resume("txn")), testProbe.ref)
        transactionProbe.expectMessageType[Transaction.Resume]
        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectNoMessage(1.second)
      }
      "Do nothing on StreamFailure" in {
        stubTransactionResolver expects (*) never

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! PrepareForShutdown(shutdownProbe.ref)
        transactionProbe.expectNoMessage(1.second)
        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectNoMessage(1.second)
      }

      "change behavior on StreamCompleted" in {
        stubTransactionResolver expects (*) never

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! StreamCompleted
        transactionProbe.expectNoMessage(1.second)
        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectNoMessage(1.second)

        underTest ! Op(Operation.Resume(kafka_operations.Resume("txn")), testProbe.ref)
        transactionProbe.expectNoMessage(1.second)
        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectNoMessage(1.second)
      }

      "stop on StreamCompleted when PrepareForShutdown is received beforehand" in {
        stubTransactionResolver expects (*) never

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! PrepareForShutdown(shutdownProbe.ref)
        transactionProbe.expectNoMessage(1.second)
        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectNoMessage(1.second)

        underTest ! StreamCompleted
        transactionProbe.expectNoMessage(1.second)
        shutdownProbe.expectMessageType[Done]
        testProbe.expectNoMessage(1.second)

        testProbe.expectTerminated(underTest)
      }

      "stop on StreamCompleted when PrepareForShutdown is received afterwards" in {
        stubTransactionResolver expects (*) never

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        underTest ! StreamCompleted
        transactionProbe.expectNoMessage(1.second)
        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectNoMessage(1.second)

        underTest ! PrepareForShutdown(shutdownProbe.ref)
        transactionProbe.expectNoMessage(1.second)
        shutdownProbe.expectMessageType[Done]
        testProbe.expectNoMessage(1.second)

        testProbe.expectTerminated(underTest)
      }
    }
    "integrated into a stream" must {
      "be prepared to stop when the stream is completed" in {
        stubTransactionResolver expects "txn" returning transactionProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))
        Source(Seq("NO_OP", "OP"))
          .runWith(ActorSink.actorRefWithBackpressure(
            ref = underTest,
            onInitMessage = replyTo => StreamInitialized(replyTo),
            ackMessage = StreamAck,
            onCompleteMessage = StreamCompleted,
            onFailureMessage = ex => StreamFailure(ex),
            messageAdapter = (replyTo: ActorRef[StreamAck], message: String) => message match {
              case "NO_OP" =>
                Op(Operation.Empty, replyTo)
              case "OP" =>
                Op(Operation.Resume(kafka_operations.Resume("txn")), replyTo)
            }))

        transactionProbe.expectMessageType[Resume].replyTo ! Ack
        shutdownProbe.expectNoMessage(1.second)

        underTest ! Op(Operation.Resume(kafka_operations.Resume("txn")), testProbe.ref)
        transactionProbe.expectNoMessage(1.second)
        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectNoMessage(1.second)

        underTest ! PrepareForShutdown(shutdownProbe.ref)
        transactionProbe.expectNoMessage(1.second)
        shutdownProbe.expectMessageType[Done]

        testProbe.expectTerminated(underTest)
      }

      "stop immediately after stream is completed when told to prepare" in {
        stubTransactionResolver expects "txn" returning transactionProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver, stubResultMessenger))

        underTest ! PrepareForShutdown(shutdownProbe.ref)
        transactionProbe.expectNoMessage(1.second)
        shutdownProbe.expectNoMessage(1.second)

        Source(Seq("NO_OP", "OP"))
          .runWith(ActorSink.actorRefWithBackpressure(
            ref = underTest,
            onInitMessage = replyTo => StreamInitialized(replyTo),
            ackMessage = StreamAck,
            onCompleteMessage = StreamCompleted,
            onFailureMessage = ex => StreamFailure(ex),
            messageAdapter = (replyTo: ActorRef[StreamAck], message: String) => message match {
              case "NO_OP" =>
                Op(Operation.Empty, replyTo)
              case "OP" =>
                Op(Operation.Resume(kafka_operations.Resume("txn")), replyTo)
            }))

        transactionProbe.expectMessageType[Resume].replyTo ! Nack
        shutdownProbe.expectMessageType[Done]

        testProbe.expectTerminated(underTest)
      }
    }
  }
}
