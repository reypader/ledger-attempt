package io.openledger

import akka.Done
import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.actor.typed.ActorRef
import akka.stream.scaladsl.Source
import akka.stream.typed.scaladsl.ActorSink
import io.openledger.StreamConsumer._
import io.openledger.domain.transaction.Transaction
import io.openledger.domain.transaction.Transaction.{Resume, TransactionCommand, TxnAck}
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
  private val testProbe = testKit.createTestProbe[TxnAck]
  private val transactionProbe = testKit.createTestProbe[TransactionCommand]

  private val stubTransactionResolver = mockFunction[String, ActorRef[TransactionCommand]]


  "StreamConsumer" when {
    "tested in isolation" must {
      "Ack on StreamInitialized" in {
        stubTransactionResolver expects (*) never

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver))
        underTest ! StreamInitialized(testProbe.ref)
        transactionProbe.expectNoMessage(1.second)
        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectMessageType[TxnAck]
      }
      "Ack on NoOp" in {
        stubTransactionResolver expects (*) never

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver))
        underTest ! NoOp(testProbe.ref)
        transactionProbe.expectNoMessage(1.second)
        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectMessageType[TxnAck]
      }
      "Forward to probe on Op" in {
        stubTransactionResolver expects "txn" returning transactionProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver))
        underTest ! Op("txn", Transaction.Resume(testProbe.ref))
        transactionProbe.expectMessageType[Transaction.Resume]
        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectNoMessage(1.second)
      }
      "Stash message on PrepareForShutdown" in {
        stubTransactionResolver expects "txn" returning transactionProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver))
        underTest ! PrepareForShutdown(shutdownProbe.ref)
        transactionProbe.expectNoMessage(1.second)
        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectNoMessage(1.second)

        underTest ! Op("txn", Transaction.Resume(testProbe.ref))
        transactionProbe.expectMessageType[Transaction.Resume]
        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectNoMessage(1.second)
      }
      "Do nothing on StreamFailure" in {
        stubTransactionResolver expects (*) never

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver))
        underTest ! PrepareForShutdown(shutdownProbe.ref)
        transactionProbe.expectNoMessage(1.second)
        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectNoMessage(1.second)
      }

      "change behavior on StreamCompleted" in {
        stubTransactionResolver expects (*) never

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver))
        underTest ! StreamCompleted
        transactionProbe.expectNoMessage(1.second)
        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectNoMessage(1.second)

        underTest ! Op("txn", Transaction.Resume(testProbe.ref))
        transactionProbe.expectNoMessage(1.second)
        shutdownProbe.expectNoMessage(1.second)
        testProbe.expectNoMessage(1.second)
      }

      "stop on StreamCompleted when PrepareForShutdown is received beforehand" in {
        stubTransactionResolver expects (*) never

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver))
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

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver))
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

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver))
        Source(Seq("NO_OP", "OP"))
          .runWith(ActorSink.actorRefWithBackpressure(
            ref = underTest,
            onInitMessage = replyTo => StreamInitialized(replyTo),
            ackMessage = Transaction.Ack,
            onCompleteMessage = StreamCompleted,
            onFailureMessage = ex => StreamFailure(ex),
            messageAdapter = (replyTo: ActorRef[TxnAck], message: String) => message match {
              case "NO_OP" =>
                NoOp(replyTo)
              case "OP" =>
                Op("txn", Transaction.Resume(replyTo))
            }))

        transactionProbe.expectMessageType[Resume]
        shutdownProbe.expectNoMessage(1.second)

        underTest ! Op("txn", Transaction.Resume(testProbe.ref))
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

        val underTest = testKit.spawn(StreamConsumer(stubTransactionResolver))

        underTest ! PrepareForShutdown(shutdownProbe.ref)
        transactionProbe.expectNoMessage(1.second)
        shutdownProbe.expectNoMessage(1.second)

        Source(Seq("NO_OP", "OP"))
          .runWith(ActorSink.actorRefWithBackpressure(
            ref = underTest,
            onInitMessage = replyTo => StreamInitialized(replyTo),
            ackMessage = Transaction.Ack,
            onCompleteMessage = StreamCompleted,
            onFailureMessage = ex => StreamFailure(ex),
            messageAdapter = (replyTo: ActorRef[TxnAck], message: String) => message match {
              case "NO_OP" =>
                NoOp(replyTo)
              case "OP" =>
                Op("txn", Transaction.Resume(replyTo))
            }))

        transactionProbe.expectMessageType[Resume]
        shutdownProbe.expectMessageType[Done]

        testProbe.expectTerminated(underTest)
      }
    }
  }
}
