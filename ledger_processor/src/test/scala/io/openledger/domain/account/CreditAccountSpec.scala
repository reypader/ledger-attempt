package io.openledger.domain.account

import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.typesafe.config.ConfigFactory
import io.openledger.domain.account.Account._
import io.openledger.domain.account.states.{AccountState, CreditAccount}
import io.openledger.events._
import io.openledger.{AccountingMode, DateUtils, LedgerError}
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Duration
import java.util.UUID
import scala.language.postfixOps

class CreditAccountSpec
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

  private val theTime = DateUtils.now()
  private val entryCode = "ENTRY"
  private val stubMessenger = mockFunction[String, AccountingStatus, Unit]
  private val txnId = UUID.randomUUID().toString
  private val accountId = UUID.randomUUID().toString
  private val eventSourcedTestKit = EventSourcedBehaviorTestKit[AccountCommand, AccountEvent, AccountState](
    system,
    Account(accountId)(stubMessenger, () => theTime)
  )

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    eventSourcedTestKit.runCommand(Open(AccountingMode.CREDIT, Set.empty))
  }

  override protected def afterEach(): Unit = {
    super.afterEach()
    eventSourcedTestKit.clear()
  }

  "A Credit Account" when {

    "at initially 0/0/0 balance" must {
      def given(): Unit = {}

      "reject Debit(uuid, 1) with CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE" in {
        given()
        val cmd = Debit(txnId, entryCode, 1)
        stubMessenger expects (txnId, AccountingFailed(
          cmd.hashCode(),
          accountId,
          LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE
        )) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.hasNoEvents shouldBe true
        result.stateOfType[CreditAccount].availableBalance shouldBe 0
        result.stateOfType[CreditAccount].currentBalance shouldBe 0
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "accept Credit(uuid, 1) and have 1/1/0 balance" in {
        given()
        val cmd = Credit(txnId, entryCode, 1)
        stubMessenger expects (txnId, AccountingSuccessful(cmd.hashCode(), accountId, 1, 1, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.events shouldBe Seq(Credited(txnId, entryCode, 1, 1, 1, theTime))
        result.stateOfType[CreditAccount].availableBalance shouldBe 1
        result.stateOfType[CreditAccount].currentBalance shouldBe 1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "reject DebitAuthorize(uuid, 1) with CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE" in {
        given()
        val cmd = DebitAuthorize(txnId, entryCode, 1)
        stubMessenger expects (txnId, AccountingFailed(
          cmd.hashCode(),
          accountId,
          LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE
        )) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.hasNoEvents shouldBe true
        result.stateOfType[CreditAccount].availableBalance shouldBe 0
        result.stateOfType[CreditAccount].currentBalance shouldBe 0
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "reject DebitCapture(uuid, 1,0) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        val cmd = DebitCapture(txnId, entryCode, 1, 0, theTime)
        stubMessenger expects (txnId, AccountingFailed(
          cmd.hashCode(),
          accountId,
          LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE
        )) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.hasNoEvents shouldBe true
        result.stateOfType[CreditAccount].availableBalance shouldBe 0
        result.stateOfType[CreditAccount].currentBalance shouldBe 0
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "reject DebitRelease(uuid, 1) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        val cmd = DebitRelease(txnId, entryCode, 1)
        stubMessenger expects (txnId, AccountingFailed(
          cmd.hashCode(),
          accountId,
          LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE
        )) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.hasNoEvents shouldBe true
        result.stateOfType[CreditAccount].availableBalance shouldBe 0
        result.stateOfType[CreditAccount].currentBalance shouldBe 0
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "accept DebitAdjust(uuid, 1) and have -1/-1/0 balance with Overdrawn" in {
        given()
        val cmd = DebitAdjust(txnId, entryCode, 1)
        stubMessenger expects (txnId, AccountingSuccessful(cmd.hashCode(), accountId, -1, -1, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.events shouldBe Seq(Debited(txnId, entryCode, 1, -1, -1, theTime), Overdrawn(txnId, entryCode, theTime))
        result.stateOfType[CreditAccount].availableBalance shouldBe -1
        result.stateOfType[CreditAccount].currentBalance shouldBe -1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "accept CreditAdjust(uuid, 1) and have 1/1/0 balance" in {
        given()
        val cmd = CreditAdjust(txnId, entryCode, 1)
        stubMessenger expects (txnId, AccountingSuccessful(cmd.hashCode(), accountId, 1, 1, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.events shouldBe Seq(Credited(txnId, entryCode, 1, 1, 1, theTime))
        result.stateOfType[CreditAccount].availableBalance shouldBe 1
        result.stateOfType[CreditAccount].currentBalance shouldBe 1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }
    }

    "at initially 1/1/0 balance" must {
      def given(): Unit = {
        val cmd = Credit(txnId, entryCode, 1)
        stubMessenger expects (txnId, AccountingSuccessful(cmd.hashCode(), accountId, 1, 1, 0, theTime)) once

        eventSourcedTestKit.runCommand(cmd)
      }

      "accept Debit(uuid, 1) and have 0/0/0 balance" in {
        given()
        val cmd = Debit(txnId, entryCode, 1)
        stubMessenger expects (txnId, AccountingSuccessful(cmd.hashCode(), accountId, 0, 0, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.events shouldBe Seq(Debited(txnId, entryCode, 1, 0, 0, theTime))
        result.stateOfType[CreditAccount].availableBalance shouldBe 0
        result.stateOfType[CreditAccount].currentBalance shouldBe 0
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "accept Credit(uuid, 1) and have 2/2/0 balance" in {
        given()
        val cmd = Credit(txnId, entryCode, 1)
        stubMessenger expects (txnId, AccountingSuccessful(cmd.hashCode(), accountId, 2, 2, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.events shouldBe Seq(Credited(txnId, entryCode, 1, 2, 2, theTime))
        result.stateOfType[CreditAccount].availableBalance shouldBe 2
        result.stateOfType[CreditAccount].currentBalance shouldBe 2
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "accept DebitAuthorize(uuid, 1) and have 0/1/1 balance" in {
        given()
        val cmd = DebitAuthorize(txnId, entryCode, 1)
        stubMessenger expects (txnId, AccountingSuccessful(cmd.hashCode(), accountId, 0, 1, 1, theTime)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.events shouldBe Seq(DebitAuthorized(txnId, entryCode, 1, 0, 1, theTime))
        result.stateOfType[CreditAccount].availableBalance shouldBe 0
        result.stateOfType[CreditAccount].currentBalance shouldBe 1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 1
      }

      "reject DebitAuthorize(uuid, 2) with CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE" in {
        given()
        val cmd = DebitAuthorize(txnId, entryCode, 2)
        stubMessenger expects (txnId, AccountingFailed(
          cmd.hashCode(),
          accountId,
          LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE
        )) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.hasNoEvents shouldBe true
        result.stateOfType[CreditAccount].availableBalance shouldBe 1
        result.stateOfType[CreditAccount].currentBalance shouldBe 1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "reject DebitCapture(uuid, 1,0) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        val cmd = DebitCapture(txnId, entryCode, 1, 0, theTime)
        stubMessenger expects (txnId, AccountingFailed(
          cmd.hashCode(),
          accountId,
          LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE
        )) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.hasNoEvents shouldBe true
        result.stateOfType[CreditAccount].availableBalance shouldBe 1
        result.stateOfType[CreditAccount].currentBalance shouldBe 1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "reject DebitRelease(uuid, 1) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        val cmd = DebitRelease(txnId, entryCode, 1)
        stubMessenger expects (txnId, AccountingFailed(
          cmd.hashCode(),
          accountId,
          LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE
        )) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.hasNoEvents shouldBe true
        result.stateOfType[CreditAccount].availableBalance shouldBe 1
        result.stateOfType[CreditAccount].currentBalance shouldBe 1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "accept DebitAdjust(uuid, 1) and have 0/0/0 balance" in {
        given()
        val cmd = DebitAdjust(txnId, entryCode, 1)
        stubMessenger expects (txnId, AccountingSuccessful(cmd.hashCode(), accountId, 0, 0, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.events shouldBe Seq(Debited(txnId, entryCode, 1, 0, 0, theTime))
        result.stateOfType[CreditAccount].availableBalance shouldBe 0
        result.stateOfType[CreditAccount].currentBalance shouldBe 0
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "accept CreditAdjust(uuid, 1) and have 2/2/0 balance" in {
        given()
        val cmd = CreditAdjust(txnId, entryCode, 1)
        stubMessenger expects (txnId, AccountingSuccessful(cmd.hashCode(), accountId, 2, 2, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.events shouldBe Seq(Credited(txnId, entryCode, 1, 2, 2, theTime))
        result.stateOfType[CreditAccount].availableBalance shouldBe 2
        result.stateOfType[CreditAccount].currentBalance shouldBe 2
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

    }

    "at initially 0/1/1 balance" must {
      def given(): Unit = {
        val cmd1 = Credit(txnId, entryCode, 1)
        stubMessenger expects (txnId, AccountingSuccessful(cmd1.hashCode(), accountId, 1, 1, 0, theTime)) once

        eventSourcedTestKit.runCommand(cmd1)

        val cmd2 = DebitAuthorize(txnId, entryCode, 1)
        stubMessenger expects (txnId, AccountingSuccessful(cmd2.hashCode(), accountId, 0, 1, 1, theTime)) once

        eventSourcedTestKit.runCommand(cmd2)
      }

      "reject Debit(uuid, 1) with CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE" in {
        given()
        val cmd = Debit(txnId, entryCode, 1)
        stubMessenger expects (txnId, AccountingFailed(
          cmd.hashCode(),
          accountId,
          LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE
        )) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.hasNoEvents shouldBe true
        result.stateOfType[CreditAccount].availableBalance shouldBe 0
        result.stateOfType[CreditAccount].currentBalance shouldBe 1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 1
      }

      "accept Credit(uuid, 1) and have 1/2/1 balance" in {
        given()
        val cmd = Credit(txnId, entryCode, 1)
        stubMessenger expects (txnId, AccountingSuccessful(cmd.hashCode(), accountId, 1, 2, 1, theTime)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.events shouldBe Seq(Credited(txnId, entryCode, 1, 1, 2, theTime))
        result.stateOfType[CreditAccount].availableBalance shouldBe 1
        result.stateOfType[CreditAccount].currentBalance shouldBe 2
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 1
      }

      "reject DebitAuthorize(uuid, 1) with CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE" in {
        given()
        val cmd = DebitAuthorize(txnId, entryCode, 1)
        stubMessenger expects (txnId, AccountingFailed(
          cmd.hashCode(),
          accountId,
          LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE
        )) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.hasNoEvents shouldBe true
        result.stateOfType[CreditAccount].availableBalance shouldBe 0
        result.stateOfType[CreditAccount].currentBalance shouldBe 1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 1
      }

      "accept DebitCapture(uuid, 1,0) and have 0/0/0 balance" in {
        given()
        val yesterday = DateUtils.now().minus(Duration.ofDays(1))

        val cmd = DebitCapture(txnId, entryCode, 1, 0, yesterday)
        stubMessenger expects (txnId, AccountingSuccessful(cmd.hashCode(), accountId, 0, 0, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.events shouldBe Seq(DebitCaptured(txnId, entryCode, 1, 0, 0, 0, 0, yesterday, theTime))
        result.stateOfType[CreditAccount].availableBalance shouldBe 0
        result.stateOfType[CreditAccount].currentBalance shouldBe 0
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "reject DebitCapture(uuid, 1,1) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        val cmd = DebitCapture(txnId, entryCode, 1, 1, theTime)
        stubMessenger expects (txnId, AccountingFailed(
          cmd.hashCode(),
          accountId,
          LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE
        )) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.hasNoEvents shouldBe true
        result.stateOfType[CreditAccount].availableBalance shouldBe 0
        result.stateOfType[CreditAccount].currentBalance shouldBe 1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 1
      }

      "reject DebitCapture(uuid, 2,0) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        val cmd = DebitCapture(txnId, entryCode, 2, 0, theTime)
        stubMessenger expects (txnId, AccountingFailed(
          cmd.hashCode(),
          accountId,
          LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE
        )) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.hasNoEvents shouldBe true
        result.stateOfType[CreditAccount].availableBalance shouldBe 0
        result.stateOfType[CreditAccount].currentBalance shouldBe 1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 1
      }

      "accept DebitRelease(uuid, 1) and have 1/1/0 balance" in {
        given()
        val cmd = DebitRelease(txnId, entryCode, 1)
        stubMessenger expects (txnId, AccountingSuccessful(cmd.hashCode(), accountId, 1, 1, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.events shouldBe Seq(DebitReleased(txnId, entryCode, 1, 1, 0, theTime))
        result.stateOfType[CreditAccount].availableBalance shouldBe 1
        result.stateOfType[CreditAccount].currentBalance shouldBe 1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "reject DebitRelease(uuid, 2) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        val cmd = DebitRelease(txnId, entryCode, 2)
        stubMessenger expects (txnId, AccountingFailed(
          cmd.hashCode(),
          accountId,
          LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE
        )) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.hasNoEvents shouldBe true
        result.stateOfType[CreditAccount].availableBalance shouldBe 0
        result.stateOfType[CreditAccount].currentBalance shouldBe 1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 1
      }

    }

    "at initially 0/2/2 balance" must {
      def given(): Unit = {
        val cmd1 = Credit(txnId, entryCode, 2)
        stubMessenger expects (txnId, AccountingSuccessful(cmd1.hashCode(), accountId, 2, 2, 0, theTime)) once

        eventSourcedTestKit.runCommand(cmd1)

        val cmd2 = DebitAuthorize(txnId, entryCode, 2)
        stubMessenger expects (txnId, AccountingSuccessful(cmd2.hashCode(), accountId, 0, 2, 2, theTime)) once

        eventSourcedTestKit.runCommand(cmd2)
      }

      "accept DebitCapture(uuid, 1,1) and have 1/1/0 balance" in {
        given()
        val yesterday = DateUtils.now().minus(Duration.ofDays(1))

        val cmd = DebitCapture(txnId, entryCode, 1, 1, yesterday)
        stubMessenger expects (txnId, AccountingSuccessful(cmd.hashCode(), accountId, 1, 1, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.events shouldBe Seq(DebitCaptured(txnId, entryCode, 1, 1, 1, 1, 0, yesterday, theTime))
        result.stateOfType[CreditAccount].availableBalance shouldBe 1
        result.stateOfType[CreditAccount].currentBalance shouldBe 1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "accept DebitAdjust(uuid, 1), DebitCapture(uuid, 2,0) and have -1/-1/0 balance" in {
        given()
        val cmd1 = DebitAdjust(txnId, entryCode, 1)
        stubMessenger expects (txnId, AccountingSuccessful(cmd1.hashCode(), accountId, -1, 1, 2, theTime)) once
        val result1 = eventSourcedTestKit.runCommand(cmd1)
        result1.events shouldBe Seq(Debited(txnId, entryCode, 1, -1, 1, theTime), Overdrawn(txnId, entryCode, theTime))
        result1.stateOfType[CreditAccount].availableBalance shouldBe -1
        result1.stateOfType[CreditAccount].currentBalance shouldBe 1
        result1.stateOfType[CreditAccount].authorizedBalance shouldBe 2

        val yesterday = DateUtils.now().minus(Duration.ofDays(1))

        val cmd2 = DebitCapture(txnId, entryCode, 2, 0, yesterday)
        stubMessenger expects (txnId, AccountingSuccessful(cmd2.hashCode(), accountId, -1, -1, 0, theTime)) once
        val result2 = eventSourcedTestKit.runCommand(cmd2)
        result2.events shouldBe Seq(
          DebitCaptured(txnId, entryCode, 2, 0, -1, -1, 0, yesterday, theTime),
          Overdrawn(txnId, entryCode, theTime)
        )
        result2.stateOfType[CreditAccount].availableBalance shouldBe -1
        result2.stateOfType[CreditAccount].currentBalance shouldBe -1
        result2.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }
    }
  }
}
