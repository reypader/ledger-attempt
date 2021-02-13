package io.openledger.api.kafka

import akka.Done
import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.{ActorRef, Scheduler}
import akka.kafka.ConsumerMessage.Committable
import akka.persistence.typed.scaladsl.Effect
import io.openledger.api.kafka.StreamConsumer._
import io.openledger.domain.account.Account
import io.openledger.domain.account.Account.{AccAck, AccountCommand, Ping}
import io.openledger.domain.entry.Entry
import io.openledger.domain.entry.Entry.{apply => _, _}
import io.openledger.domain.entry.states.{EntryState, PairedEntry}
import io.openledger.events.EntryEvent
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
  private val accountProbeA = testKit.createTestProbe[AccountCommand]
  private val accountProbeB = testKit.createTestProbe[AccountCommand]
  private val stubResultMessenger = mockFunction[EntryResult, Unit]
  private val offset = StubCommittable()
  private val stubEntryResolver = mockFunction[String, ActorRef[EntryCommand]]
  private val stubAccountResolver = mockFunction[String, ActorRef[AccountCommand]]
  private implicit val scheduler: Scheduler = testKit.system.scheduler
  private implicit val executionContext: ExecutionContextExecutor = testKit.system.executionContext
  private val stubPairedEntry = StubEntry()

  override protected def afterEach(): Unit = {
    super.afterEach()
    testProbe.expectNoMessage()
    entryProbe.expectNoMessage()
    accountProbeA.expectNoMessage()
    accountProbeB.expectNoMessage()
  }

  "StreamConsumer" when {
    "tested in isolation" must {
      "Ack on NoOp" in {
        stubAccountResolver expects * never

        stubEntryResolver expects * never

        val underTest = testKit.spawn(StreamConsumer(stubEntryResolver, stubAccountResolver, stubResultMessenger))
        underTest ! Receive(StreamMessage(Operation.Empty, offset), testProbe.ref)
        entryProbe.expectNoMessage(1.second)

        testProbe.expectMessageType[StubCommittable]
      }
      "Forward to probe on Op-Reverse" in {
        stubEntryResolver expects "txn" returning entryProbe.ref once

        stubAccountResolver expects "d" returning accountProbeA.ref once

        stubAccountResolver expects "c" returning accountProbeB.ref once

        val underTest = testKit.spawn(StreamConsumer(stubEntryResolver, stubAccountResolver, stubResultMessenger))
        underTest ! Receive(StreamMessage(Operation.Reverse(kafka_operations.Reverse("txn")), offset), testProbe.ref)

        entryProbe.expectMessageType[Get].replyTo ! stubPairedEntry
        accountProbeA.expectMessageType[Ping].replyTo ! Account.Ack
        accountProbeB.expectMessageType[Ping].replyTo ! Account.Ack

        entryProbe.expectMessageType[Entry.Reverse].replyTo ! Ack

        testProbe.expectMessageType[StubCommittable]
      }
      "Forward to probe on Op-Simple" in {
        stubAccountResolver expects "d" returning accountProbeA.ref once

        stubAccountResolver expects "c" returning accountProbeB.ref once

        stubEntryResolver expects "txn" returning entryProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubEntryResolver, stubAccountResolver, stubResultMessenger))
        underTest ! Receive(
          StreamMessage(Operation.Simple(kafka_operations.Simple("ec", "txn", "d", "c", 1.5)), offset),
          testProbe.ref
        )
        accountProbeA.expectMessageType[Ping].replyTo ! Account.Ack
        accountProbeB.expectMessageType[Ping].replyTo ! Account.Ack

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
        stubAccountResolver expects "d" returning accountProbeA.ref once

        stubAccountResolver expects "c" returning accountProbeB.ref once

        stubEntryResolver expects "txn" returning entryProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubEntryResolver, stubAccountResolver, stubResultMessenger))
        underTest ! Receive(
          StreamMessage(Operation.Authorize(kafka_operations.Authorize("ec", "txn", "d", "c", 1.5)), offset),
          testProbe.ref
        )

        accountProbeA.expectMessageType[Ping].replyTo ! Account.Ack
        accountProbeB.expectMessageType[Ping].replyTo ! Account.Ack

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

        stubAccountResolver expects "d" returning accountProbeA.ref once

        stubAccountResolver expects "c" returning accountProbeB.ref once

        val underTest = testKit.spawn(StreamConsumer(stubEntryResolver, stubAccountResolver, stubResultMessenger))
        underTest ! Receive(
          StreamMessage(Operation.Capture(kafka_operations.Capture("txn", 1.5)), offset),
          testProbe.ref
        )

        entryProbe.expectMessageType[Get].replyTo ! stubPairedEntry
        accountProbeA.expectMessageType[Ping].replyTo ! Account.Ack
        accountProbeB.expectMessageType[Ping].replyTo ! Account.Ack

        val sniffed = entryProbe.expectMessageType[Entry.Capture]
        sniffed.captureAmount shouldBe 1.5
        sniffed.replyTo ! Ack

        testProbe.expectMessageType[StubCommittable]
      }

      "Forward to probe on Op-Reverse - Nack" in {
        stubEntryResolver expects "txn" returning entryProbe.ref once

        stubAccountResolver expects "d" returning accountProbeA.ref once

        stubAccountResolver expects "c" returning accountProbeB.ref once

        val underTest = testKit.spawn(StreamConsumer(stubEntryResolver, stubAccountResolver, stubResultMessenger))
        underTest ! Receive(StreamMessage(Operation.Reverse(kafka_operations.Reverse("txn")), offset), testProbe.ref)

        entryProbe.expectMessageType[Get].replyTo ! stubPairedEntry
        accountProbeA.expectMessageType[Ping].replyTo ! Account.Ack
        accountProbeB.expectMessageType[Ping].replyTo ! Account.Ack

        entryProbe.expectMessageType[Entry.Reverse].replyTo ! Nack

        testProbe.expectMessageType[StubCommittable]
      }

      "Forward to probe on Op-Reverse - Timeout" in {
        stubEntryResolver expects "txn" returning entryProbe.ref once

        stubAccountResolver expects "d" returning accountProbeA.ref once

        stubAccountResolver expects "c" returning accountProbeB.ref once

        stubResultMessenger expects CommandThrottled("txn") once

        val underTest = testKit.spawn(StreamConsumer(stubEntryResolver, stubAccountResolver, stubResultMessenger))
        underTest ! Receive(StreamMessage(Operation.Reverse(kafka_operations.Reverse("txn")), offset), testProbe.ref)
        entryProbe.expectMessageType[Get].replyTo ! stubPairedEntry
        accountProbeA.expectMessageType[Ping]
        accountProbeB.expectMessageType[Ping]

        entryProbe.expectNoMessage(10.seconds)
        testProbe.expectMessageType[StubCommittable](10.seconds)
      }

      "Forward to probe on Op-Simple - Nack" in {
        stubAccountResolver expects "d" returning accountProbeA.ref once

        stubAccountResolver expects "c" returning accountProbeB.ref once

        stubEntryResolver expects "txn" returning entryProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubEntryResolver, stubAccountResolver, stubResultMessenger))
        underTest ! Receive(
          StreamMessage(Operation.Simple(kafka_operations.Simple("ec", "txn", "d", "c", 1.5)), offset),
          testProbe.ref
        )
        accountProbeA.expectMessageType[Ping].replyTo ! Account.Ack
        accountProbeB.expectMessageType[Ping].replyTo ! Account.Ack
        val sniffed = entryProbe.expectMessageType[Entry.Begin]
        sniffed.authOnly shouldBe false
        sniffed.entryCode shouldBe "ec"
        sniffed.accountToCredit shouldBe "c"
        sniffed.accountToDebit shouldBe "d"
        sniffed.amount shouldBe 1.5
        sniffed.replyTo ! Nack

        testProbe.expectMessageType[StubCommittable]
      }

      "Forward to probe on Op-Simple - Timeout" in {
        stubAccountResolver expects "d" returning accountProbeA.ref once

        stubAccountResolver expects "c" returning accountProbeB.ref once

        stubResultMessenger expects CommandThrottled("txn") once

        stubEntryResolver expects * never

        val underTest = testKit.spawn(StreamConsumer(stubEntryResolver, stubAccountResolver, stubResultMessenger))
        underTest ! Receive(
          StreamMessage(Operation.Simple(kafka_operations.Simple("ec", "txn", "d", "c", 1.5)), offset),
          testProbe.ref
        )
        accountProbeA.expectMessageType[Ping]
        accountProbeB.expectMessageType[Ping]

        entryProbe.expectNoMessage(10.seconds)
        testProbe.expectMessageType[StubCommittable](10.seconds)
      }

      "Forward to probe on Op-Authorize - Nack" in {
        stubAccountResolver expects "d" returning accountProbeA.ref once

        stubAccountResolver expects "c" returning accountProbeB.ref once

        stubEntryResolver expects "txn" returning entryProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubEntryResolver, stubAccountResolver, stubResultMessenger))
        underTest ! Receive(
          StreamMessage(Operation.Authorize(kafka_operations.Authorize("ec", "txn", "d", "c", 1.5)), offset),
          testProbe.ref
        )
        accountProbeA.expectMessageType[Ping].replyTo ! Account.Ack
        accountProbeB.expectMessageType[Ping].replyTo ! Account.Ack
        val sniffed = entryProbe.expectMessageType[Entry.Begin]
        sniffed.authOnly shouldBe true
        sniffed.entryCode shouldBe "ec"
        sniffed.accountToCredit shouldBe "c"
        sniffed.accountToDebit shouldBe "d"
        sniffed.amount shouldBe 1.5
        sniffed.replyTo ! Nack

        testProbe.expectMessageType[StubCommittable]
      }

      "Forward to probe on Op-Authorize - Timeout" in {
        stubAccountResolver expects "d" returning accountProbeA.ref once

        stubAccountResolver expects "c" returning accountProbeB.ref once

        stubResultMessenger expects CommandThrottled("txn") once

        stubEntryResolver expects * never

        val underTest = testKit.spawn(StreamConsumer(stubEntryResolver, stubAccountResolver, stubResultMessenger))
        underTest ! Receive(
          StreamMessage(Operation.Authorize(kafka_operations.Authorize("ec", "txn", "d", "c", 1.5)), offset),
          testProbe.ref
        )
        accountProbeA.expectMessageType[Ping]
        accountProbeB.expectMessageType[Ping]

        entryProbe.expectNoMessage(10.seconds)
        testProbe.expectMessageType[StubCommittable](10.seconds)
      }

      "Forward to probe on Op-Capture - Nack" in {
        stubAccountResolver expects "d" returning accountProbeA.ref once

        stubAccountResolver expects "c" returning accountProbeB.ref once

        stubEntryResolver expects "txn" returning entryProbe.ref once

        val underTest = testKit.spawn(StreamConsumer(stubEntryResolver, stubAccountResolver, stubResultMessenger))
        underTest ! Receive(
          StreamMessage(Operation.Capture(kafka_operations.Capture("txn", 1.5)), offset),
          testProbe.ref
        )
        entryProbe.expectMessageType[Get].replyTo ! stubPairedEntry
        accountProbeA.expectMessageType[Ping].replyTo ! Account.Ack
        accountProbeB.expectMessageType[Ping].replyTo ! Account.Ack
        val sniffed = entryProbe.expectMessageType[Entry.Capture]
        sniffed.captureAmount shouldBe 1.5
        sniffed.replyTo ! Nack

        testProbe.expectMessageType[StubCommittable]
      }

      "Forward to probe on Op-Capture - Timeout" in {
        stubEntryResolver expects "txn" returning entryProbe.ref once

        stubAccountResolver expects "d" returning accountProbeA.ref once

        stubAccountResolver expects "c" returning accountProbeB.ref once

        stubResultMessenger expects CommandThrottled("txn") once

        val underTest = testKit.spawn(StreamConsumer(stubEntryResolver, stubAccountResolver, stubResultMessenger))
        underTest ! Receive(
          StreamMessage(Operation.Capture(kafka_operations.Capture("txn", 1.5)), offset),
          testProbe.ref
        )
        entryProbe.expectMessageType[Get].replyTo ! stubPairedEntry
        accountProbeA.expectMessageType[Ping]
        accountProbeB.expectMessageType[Ping]

        entryProbe.expectNoMessage(10.seconds)
        testProbe.expectMessageType[StubCommittable](10.seconds)
      }
    }
  }

  private case class StubCommittable() extends Committable {
    override def commitInternal(): Future[Done] = Future(Done)

    override def commitScaladsl(): Future[Done] = Future(Done)

    override def commitJavadsl(): CompletionStage[Done] = Future(Done.done()).toJava

    override def batchSize: Long = 1
  }

  private case class StubEntry() extends PairedEntry {
    override def accountToDebit: String = "d"

    override def accountToCredit: String = "c"

    override def handleEvent(event: EntryEvent)(implicit
        context: ActorContext[EntryCommand]
    ): PartialFunction[EntryEvent, EntryState] = PartialFunction.empty

    override def proceed()(implicit
        context: ActorContext[EntryCommand],
        accountMessenger: AccountMessenger,
        resultMessenger: ResultMessenger
    ): Unit = {}

    override def handleCommand(command: EntryCommand)(implicit
        context: ActorContext[EntryCommand],
        accountMessenger: AccountMessenger,
        resultMessenger: ResultMessenger
    ): PartialFunction[EntryCommand, Effect[EntryEvent, EntryState]] = PartialFunction.empty
  }
}
