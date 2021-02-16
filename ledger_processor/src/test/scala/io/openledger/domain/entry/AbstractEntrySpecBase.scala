package io.openledger.domain.entry

import akka.Done
import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit, TestProbe}
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.typesafe.config.ConfigFactory
import io.openledger.domain.account.Account._
import io.openledger.domain.entry.Entry.{Ack, Get, apply => _, _}
import io.openledger.domain.entry.states._
import io.openledger.events._
import io.openledger.{AccountingMode, DateUtils, LedgerError, ResultingBalance}
import org.scalactic.Equality
import org.scalamock.function.{MockFunction1, MockFunction2}
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.Inside.inside
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.{Duration, OffsetDateTime}
import java.util.UUID
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

abstract class AbstractEntrySpecBase
    extends ScalaTestWithActorTestKit(
      config = ConfigFactory
        .parseString("""
    akka.actor.allow-java-serialization = false
    akka.actor.serialization-bindings {
        "io.openledger.LedgerSerializable" = jackson-cbor
        "io.openledger.events.AccountEvent" = jackson-cbor
        "io.openledger.events.EntryEvent" = jackson-cbor
    }
    """).withFallback(EventSourcedBehaviorTestKit.config)
    )
    with AnyWordSpecLike
    with BeforeAndAfterEach
    with LogCapturing
    with MockFactory {

  val stubAccountMessenger: MockFunction2[String, AccountingCommand, Unit] =
    mockFunction[String, AccountingCommand, Unit]
  val stubResultMessenger: MockFunction1[EntryResult, Unit] = mockFunction[EntryResult, Unit]

  val authorizeTime: OffsetDateTime = DateUtils.now().minus(Duration.ofDays(1))
  val creditingTime: OffsetDateTime = authorizeTime.plus(Duration.ofHours(1))
  val captureTime: OffsetDateTime = creditingTime.plus(Duration.ofHours(1))
  val reverseCreditTime: OffsetDateTime = captureTime.plus(Duration.ofHours(1))
  val reverseDebitTime: OffsetDateTime = reverseCreditTime.plus(Duration.ofHours(1))

  val expectedTxnId: String = UUID.randomUUID().toString
  val expectedEntryCode = "ENTRY"
  val ackProbe: TestProbe[TxnAck] = testKit.createTestProbe[TxnAck]
  val expectedAccountIdToDebit: String = UUID.randomUUID().toString
  val expectedAccountIdToCredit: String = UUID.randomUUID().toString
  val expectedEntryAmount = BigDecimal(100)
  val debitAuth: DebitAuthorize = DebitAuthorize(expectedTxnId, expectedEntryCode, expectedEntryAmount)
  val fullCredit: Credit = Credit(expectedTxnId, expectedEntryCode, expectedEntryAmount)
  val fullCapture: DebitCapture =
    DebitCapture(expectedTxnId, expectedEntryCode, expectedEntryAmount, BigDecimal(0), authorizeTime)
  val fullRelease: DebitRelease = DebitRelease(expectedTxnId, expectedEntryCode, expectedEntryAmount)
  val debitAdjust: DebitAdjust = DebitAdjust(expectedTxnId, expectedEntryCode, expectedEntryAmount)
  val creditAdjust: CreditAdjust = CreditAdjust(expectedTxnId, expectedEntryCode, expectedEntryAmount)
  val eventSourcedTestKit: EventSourcedBehaviorTestKit[EntryCommand, EntryEvent, EntryState] =
    EventSourcedBehaviorTestKit[EntryCommand, EntryEvent, EntryState](
      system,
      Entry(expectedTxnId)(stubAccountMessenger, stubResultMessenger)
    )
  val expectedCaptureAmount: BigDecimal = BigDecimal(23)
  val partialCredit: Credit = Credit(expectedTxnId, expectedEntryCode, expectedCaptureAmount)
  val partialCapture: DebitCapture =
    DebitCapture(
      expectedTxnId,
      expectedEntryCode,
      expectedCaptureAmount,
      expectedEntryAmount - expectedCaptureAmount,
      authorizeTime
    )
  val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))
  val expectedCreditResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4), BigDecimal(5))
  val expecteddebitCapturedBalance: ResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))
  val expectedCreditReversedResultingBalance: ResultingBalance =
    ResultingBalance(BigDecimal(4.75), BigDecimal(5.75))
  val expectedDebitReversedResultingBalance: ResultingBalance =
    ResultingBalance(BigDecimal(4.25), BigDecimal(5.25))

  val fullPastCapture: DebitCapture =
    DebitCapture(expectedTxnId, expectedEntryCode, expectedEntryAmount, BigDecimal(0), authorizeTime)
  override def afterEach(): Unit = {
    super.afterEach()
    eventSourcedTestKit.clear()
    ackProbe.expectNoMessage()
  }

}
