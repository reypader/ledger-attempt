package io.openledger.domain.account

import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.typesafe.config.ConfigFactory
import io.openledger.domain.account.Account._
import io.openledger.domain.account.states.{AccountState, DebitAccount}
import io.openledger.events._
import io.openledger.{AccountingMode, DateUtils, LedgerError}
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Duration
import java.util.UUID
import scala.language.postfixOps

class DebitAccountSpec
  extends ScalaTestWithActorTestKit(config = ConfigFactory.parseString(
    """
    akka.actor.serialization-bindings {
        "io.openledger.LedgerSerializable" = jackson-cbor
        "io.openledger.events.AccountEvent" = jackson-cbor
        "io.openledger.events.TransactionEvent" = jackson-cbor
    }
    """).withFallback(EventSourcedBehaviorTestKit.config))
    with AnyWordSpecLike
    with BeforeAndAfterEach
    with LogCapturing
    with MockFactory {

  private val theTime = DateUtils.now()
  private val entryCode = "ENTRY"
  private val stubMessenger = mockFunction[String, AccountingStatus, Unit]
  private val txnId = UUID.randomUUID().toString
  private val accountId = UUID.randomUUID().toString
  private val eventSourcedTestKit = EventSourcedBehaviorTestKit[AccountCommand, AccountEvent, AccountState](system, Account(accountId)(stubMessenger, () => theTime))

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    eventSourcedTestKit.clear()
    eventSourcedTestKit.runCommand(Open(AccountingMode.DEBIT))
  }

  "A Debit Account" when {

    "at initially 0/0/0 balance" must {
      def given(): Unit = {}

      "reject Credit(uuid, 1) with INSUFFICIENT_BALANCE" in {
        given()
        val cmd = Credit(txnId, entryCode, 1)
        stubMessenger expects(txnId, AccountingFailed(cmd.hashCode(), accountId, LedgerError.INSUFFICIENT_BALANCE)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.hasNoEvents shouldBe true
        result.stateOfType[DebitAccount].availableBalance shouldBe 0
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept Debit(uuid, 1) and have 1/1/0 balance" in {
        given()
        val cmd = Debit(txnId, entryCode, 1)
        stubMessenger expects(txnId, AccountingSuccessful(cmd.hashCode(), accountId, 1, 1, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.events shouldBe Seq(Debited(txnId, entryCode,1, 1, 1, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept DebitHold(uuid, 1) and have 1/0/1 balance" in {
        given()
        val cmd = DebitHold(txnId, entryCode, 1)
        stubMessenger expects(txnId, AccountingSuccessful(cmd.hashCode(), accountId, 1, 0, 1, theTime)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.events shouldBe Seq(DebitAuthorized(txnId, entryCode,1, 1, 1, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 1
      }

      "reject Capture(uuid, 1,0) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        val cmd = Post(txnId, entryCode, 1, 0, theTime)
        stubMessenger expects(txnId, AccountingFailed(cmd.hashCode(), accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.hasNoEvents shouldBe true
        result.stateOfType[DebitAccount].availableBalance shouldBe 0
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "reject Release(uuid, 1) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        val cmd = Release(txnId, entryCode, 1)
        stubMessenger expects(txnId, AccountingFailed(cmd.hashCode(), accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.hasNoEvents shouldBe true
        result.stateOfType[DebitAccount].availableBalance shouldBe 0
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept CreditAdjust(uuid, 1) and have -1/-1/0 balance with Overpaid" in {
        given()
        val cmd = CreditAdjust(txnId, entryCode, 1)
        stubMessenger expects(txnId, AccountingSuccessful(cmd.hashCode(), accountId, -1, -1, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.events shouldBe Seq(Credited(txnId, entryCode,1, -1, -1, theTime), Overpaid(txnId, entryCode, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe -1
        result.stateOfType[DebitAccount].currentBalance shouldBe -1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept DebitAdjust(uuid, 1) and have 1/1/0 balance" in {
        given()
        val cmd = DebitAdjust(txnId, entryCode, 1)
        stubMessenger expects(txnId, AccountingSuccessful(cmd.hashCode(), accountId, 1, 1, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.events shouldBe Seq(Debited(txnId, entryCode,1, 1, 1, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }
    }

    "at initially 1/1/0 balance" must {
      def given(): Unit = {
        val cmd = Debit(txnId, entryCode, 1)
        stubMessenger expects(txnId, AccountingSuccessful(cmd.hashCode(), accountId, 1, 1, 0, theTime)) once
        val given = eventSourcedTestKit.runCommand(cmd)
      }

      "accept Credit(uuid, 1) and have 0/0/0 balance" in {
        given()
        val cmd = Credit(txnId, entryCode, 1)
        stubMessenger expects(txnId, AccountingSuccessful(cmd.hashCode(), accountId, 0, 0, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.events shouldBe Seq(Credited(txnId, entryCode,1, 0, 0, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 0
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept Debit(uuid, 1) and have 2/2/0 balance" in {
        given()
        val cmd = Debit(txnId, entryCode, 1)
        stubMessenger expects(txnId, AccountingSuccessful(cmd.hashCode(), accountId, 2, 2, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.events shouldBe Seq(Debited(txnId, entryCode,1, 2, 2, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 2
        result.stateOfType[DebitAccount].currentBalance shouldBe 2
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept DebitHold(uuid, 1) and have 2/1/1 balance" in {
        given()
        val cmd = DebitHold(txnId, entryCode, 1)
        stubMessenger expects(txnId, AccountingSuccessful(cmd.hashCode(), accountId, 2, 1, 1, theTime)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.events shouldBe Seq(DebitAuthorized(txnId, entryCode,1, 2, 1, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 2
        result.stateOfType[DebitAccount].currentBalance shouldBe 1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 1
      }

      "reject Capture(uuid, 1,0) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        val cmd = Post(txnId, entryCode, 1, 0, theTime)
        stubMessenger expects(txnId, AccountingFailed(cmd.hashCode(), accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.hasNoEvents shouldBe true
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "reject Release(uuid, 1) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        val cmd = Release(txnId, entryCode, 1)
        stubMessenger expects(txnId, AccountingFailed(cmd.hashCode(), accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.hasNoEvents shouldBe true
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept CreditAdjust(uuid, 1) and have 0/0/0 balance" in {
        given()
        val cmd = CreditAdjust(txnId, entryCode, 1)
        stubMessenger expects(txnId, AccountingSuccessful(cmd.hashCode(), accountId, 0, 0, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.events shouldBe Seq(Credited(txnId, entryCode,1, 0, 0, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 0
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept DebitAdjust(uuid, 1) and have 2/2/0 balance" in {
        given()
        val cmd = DebitAdjust(txnId, entryCode, 1)
        stubMessenger expects(txnId, AccountingSuccessful(cmd.hashCode(), accountId, 2, 2, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.events shouldBe Seq(Debited(txnId, entryCode,1, 2, 2, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 2
        result.stateOfType[DebitAccount].currentBalance shouldBe 2
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

    }

    "at initially 1/0/1 balance" must {
      def given(): Unit = {
        val cmd = DebitHold(txnId, entryCode, 1)
        stubMessenger expects(txnId, AccountingSuccessful(cmd.hashCode(), accountId, 1, 0, 1, theTime)) once
        val given2 = eventSourcedTestKit.runCommand(cmd)
      }

      "accept Credit(uuid, 1) and have 0/-1/1 balance with Overpaid" in {
        given()
        val cmd = Credit(txnId, entryCode, 1)
        stubMessenger expects(txnId, AccountingSuccessful(cmd.hashCode(), accountId, 0, -1, 1, theTime)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.events shouldBe Seq(Credited(txnId, entryCode,1, 0, -1, theTime), Overpaid(txnId, entryCode, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 0
        result.stateOfType[DebitAccount].currentBalance shouldBe -1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 1
      }

      "accept Debit(uuid, 1) and have 2/1/1 balance" in {
        given()
        val cmd = Debit(txnId, entryCode, 1)
        stubMessenger expects(txnId, AccountingSuccessful(cmd.hashCode(), accountId, 2, 1, 1, theTime)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.events shouldBe Seq(Debited(txnId, entryCode,1, 2, 1, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 2
        result.stateOfType[DebitAccount].currentBalance shouldBe 1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 1
      }

      "accept DebitHold(uuid, 1) and have 2/0/2 balance" in {
        given()
        val cmd = DebitHold(txnId, entryCode, 1)
        stubMessenger expects(txnId, AccountingSuccessful(cmd.hashCode(), accountId, 2, 0, 2, theTime)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.events shouldBe Seq(DebitAuthorized(txnId, entryCode,1, 2, 2, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 2
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 2
      }

      "accept Capture(uuid, 1,0) and have 1/1/0 balance" in {
        given()
        val yesterday = DateUtils.now().minus(Duration.ofDays(1))
        val cmd = Post(txnId, entryCode, 1, 0, yesterday)
        stubMessenger expects(txnId, AccountingSuccessful(cmd.hashCode(), accountId, 1, 1, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.events shouldBe Seq(DebitPosted(txnId, entryCode,1,0, 1, 1, 0, yesterday, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "reject Capture(uuid, 1,1) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        val cmd = Post(txnId, entryCode, 1, 1, theTime)
        stubMessenger expects(txnId, AccountingFailed(cmd.hashCode(), accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.hasNoEvents shouldBe true
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 1
      }

      "reject Capture(uuid, 2,0) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        val cmd = Post(txnId, entryCode, 2, 0, theTime)
        stubMessenger expects(txnId, AccountingFailed(cmd.hashCode(), accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.hasNoEvents shouldBe true
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 1
      }

      "accept Release(uuid, 1) and have 0/0/0 balance" in {
        given()
        val cmd = Release(txnId, entryCode, 1)
        stubMessenger expects(txnId, AccountingSuccessful(cmd.hashCode(), accountId, 0, 0, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.events shouldBe Seq(Released(txnId, entryCode,1, 0, 0, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 0
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "reject Release(uuid, 2) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        val cmd = Release(txnId, entryCode, 2)
        stubMessenger expects(txnId, AccountingFailed(cmd.hashCode(), accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.hasNoEvents shouldBe true
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 1
      }

    }

    "at initially 2/0/2 balance" must {
      def given(): Unit = {
        val cmd = DebitHold(txnId, entryCode, 2)
        stubMessenger expects(txnId, AccountingSuccessful(cmd.hashCode(), accountId, 2, 0, 2, theTime)) once
        val given2 = eventSourcedTestKit.runCommand(cmd)
      }

      "accept Capture(uuid, 1,1) and have 1/1/0 balance" in {
        given()
        val yesterday = DateUtils.now().minus(Duration.ofDays(1))
        val cmd = Post(txnId, entryCode, 1, 1, yesterday)
        stubMessenger expects(txnId, AccountingSuccessful(cmd.hashCode(), accountId, 1, 1, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.events shouldBe Seq(DebitPosted(txnId, entryCode,1,1, 1, 1, 0, yesterday, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept Credit(uuid, 1), Capture(uuid, 2,0) and have 1/1/0 balance" in {
        given()
        val cmd1 = Credit(txnId, entryCode, 1)
        stubMessenger expects(txnId, AccountingSuccessful(cmd1.hashCode(), accountId, 1, -1, 2, theTime)) once
        val result1 = eventSourcedTestKit.runCommand(cmd1)
        result1.events shouldBe Seq(Credited(txnId, entryCode,1, 1, -1, theTime), Overpaid(txnId, entryCode, theTime))
        result1.stateOfType[DebitAccount].availableBalance shouldBe 1
        result1.stateOfType[DebitAccount].currentBalance shouldBe -1
        result1.stateOfType[DebitAccount].authorizedBalance shouldBe 2

        val yesterday = DateUtils.now().minus(Duration.ofDays(1))
        val cmd2 = Post(txnId, entryCode, 2, 0, yesterday)
        stubMessenger expects(txnId, AccountingSuccessful(cmd2.hashCode(), accountId, 1, 1, 0, theTime)) once
        val result2 = eventSourcedTestKit.runCommand(cmd2)
        result2.events shouldBe Seq(DebitPosted(txnId, entryCode,2,0, 1, 1, 0, yesterday, theTime))
        result2.stateOfType[DebitAccount].availableBalance shouldBe 1
        result2.stateOfType[DebitAccount].currentBalance shouldBe 1
        result2.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept CreditAdjust(uuid, 2), Capture(uuid, 1,1) and have -1/-1/0 balance with Overpaid" in {
        given()
        val cmd1 = CreditAdjust(txnId, entryCode, 2)
        stubMessenger expects(txnId, AccountingSuccessful(cmd1.hashCode(), accountId, 0, -2, 2, theTime)) once
        val result1 = eventSourcedTestKit.runCommand(cmd1)
        result1.events shouldBe Seq(Credited(txnId, entryCode,2, 0, -2, theTime), Overpaid(txnId, entryCode, theTime))
        result1.stateOfType[DebitAccount].availableBalance shouldBe 0
        result1.stateOfType[DebitAccount].currentBalance shouldBe -2
        result1.stateOfType[DebitAccount].authorizedBalance shouldBe 2

        val yesterday = DateUtils.now().minus(Duration.ofDays(1))
        val cmd2 = Post(txnId, entryCode, 1, 1, yesterday)
        stubMessenger expects(txnId, AccountingSuccessful(cmd2.hashCode(), accountId, -1, -1, 0, theTime)) once
        val result2 = eventSourcedTestKit.runCommand(cmd2)
        result2.events shouldBe Seq(DebitPosted(txnId, entryCode,1,1, -1, -1, 0, yesterday, theTime), Overpaid(txnId, entryCode, theTime))
        result2.stateOfType[DebitAccount].availableBalance shouldBe -1
        result2.stateOfType[DebitAccount].currentBalance shouldBe -1
        result2.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }
    }
  }
}