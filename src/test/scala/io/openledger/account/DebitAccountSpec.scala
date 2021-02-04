package io.openledger.account

import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.typesafe.config.ConfigFactory
import io.openledger.account.Account._
import io.openledger.account.states.{AccountState, DebitAccount}
import io.openledger.{DateUtils, LedgerError}
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
        "io.openledger.JsonSerializable" = jackson-json
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
    eventSourcedTestKit.runCommand(Open(AccountMode.DEBIT))
  }

  "A Debit Account" when {

    "at initially 0/0/0 balance" must {
      def given(): Unit = {}

      "reject Credit(uuid, 1) with INSUFFICIENT_BALANCE" in {
        given()
        stubMessenger expects(txnId, AccountingFailed(accountId, LedgerError.INSUFFICIENT_BALANCE)) once
        val result = eventSourcedTestKit.runCommand(Credit(txnId, entryCode, 1))
        result.hasNoEvents shouldBe true
        result.stateOfType[DebitAccount].availableBalance shouldBe 0
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept Debit(uuid, 1) and have 1/1/0 balance" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 1, 1, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(Debit(txnId, entryCode, 1))
        result.events shouldBe Seq(Debited(txnId, entryCode, 1, 1, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept DebitHold(uuid, 1) and have 1/0/1 balance" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 1, 0, 1, theTime)) once
        val result = eventSourcedTestKit.runCommand(DebitHold(txnId, entryCode, 1))
        result.events shouldBe Seq(DebitAuthorized(txnId, entryCode, 1, 1, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 1
      }

      "reject Capture(uuid, 1,0) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        stubMessenger expects(txnId, AccountingFailed(accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)) once
        val result = eventSourcedTestKit.runCommand(Post(txnId, entryCode, 1, 0, theTime))
        result.hasNoEvents shouldBe true
        result.stateOfType[DebitAccount].availableBalance shouldBe 0
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "reject Release(uuid, 1) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        stubMessenger expects(txnId, AccountingFailed(accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)) once
        val result = eventSourcedTestKit.runCommand(Release(txnId, entryCode, 1))
        result.hasNoEvents shouldBe true
        result.stateOfType[DebitAccount].availableBalance shouldBe 0
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept CreditAdjust(uuid, 1) and have -1/-1/0 balance with Overpaid" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, -1, -1, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(CreditAdjust(txnId, entryCode, 1))
        result.events shouldBe Seq(Credited(txnId, entryCode, -1, -1, theTime), Overpaid(txnId, entryCode, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe -1
        result.stateOfType[DebitAccount].currentBalance shouldBe -1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept DebitAdjust(uuid, 1) and have 1/1/0 balance" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 1, 1, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(DebitAdjust(txnId, entryCode, 1))
        result.events shouldBe Seq(Debited(txnId, entryCode, 1, 1, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }
    }

    "at initially 1/1/0 balance" must {
      def given(): Unit = {
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 1, 1, 0, theTime)) once
        val given = eventSourcedTestKit.runCommand(Debit(txnId, entryCode, 1))
      }

      "accept Credit(uuid, 1) and have 0/0/0 balance" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 0, 0, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(Credit(txnId, entryCode, 1))
        result.events shouldBe Seq(Credited(txnId, entryCode, 0, 0, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 0
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept Debit(uuid, 1) and have 2/2/0 balance" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 2, 2, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(Debit(txnId, entryCode, 1))
        result.events shouldBe Seq(Debited(txnId, entryCode, 2, 2, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 2
        result.stateOfType[DebitAccount].currentBalance shouldBe 2
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept DebitHold(uuid, 1) and have 2/1/1 balance" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 2, 1, 1, theTime)) once
        val result = eventSourcedTestKit.runCommand(DebitHold(txnId, entryCode, 1))
        result.events shouldBe Seq(DebitAuthorized(txnId, entryCode, 2, 1, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 2
        result.stateOfType[DebitAccount].currentBalance shouldBe 1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 1
      }

      "reject Capture(uuid, 1,0) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        stubMessenger expects(txnId, AccountingFailed(accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)) once
        val result = eventSourcedTestKit.runCommand(Post(txnId, entryCode, 1, 0, theTime))
        result.hasNoEvents shouldBe true
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "reject Release(uuid, 1) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        stubMessenger expects(txnId, AccountingFailed(accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)) once
        val result = eventSourcedTestKit.runCommand(Release(txnId, entryCode, 1))
        result.hasNoEvents shouldBe true
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept CreditAdjust(uuid, 1) and have 0/0/0 balance" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 0, 0, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(CreditAdjust(txnId, entryCode, 1))
        result.events shouldBe Seq(Credited(txnId, entryCode, 0, 0, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 0
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept DebitAdjust(uuid, 1) and have 2/2/0 balance" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 2, 2, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(DebitAdjust(txnId, entryCode, 1))
        result.events shouldBe Seq(Debited(txnId, entryCode, 2, 2, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 2
        result.stateOfType[DebitAccount].currentBalance shouldBe 2
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

    }

    "at initially 1/0/1 balance" must {
      def given(): Unit = {
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 1, 0, 1, theTime)) once
        val given2 = eventSourcedTestKit.runCommand(DebitHold(txnId, entryCode, 1))
      }

      "accept Credit(uuid, 1) and have 0/-1/1 balance with Overpaid" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 0, -1, 1, theTime)) once
        val result = eventSourcedTestKit.runCommand(Credit(txnId, entryCode, 1))
        result.events shouldBe Seq(Credited(txnId, entryCode, 0, -1, theTime), Overpaid(txnId, entryCode, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 0
        result.stateOfType[DebitAccount].currentBalance shouldBe -1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 1
      }

      "accept Debit(uuid, 1) and have 2/1/1 balance" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 2, 1, 1, theTime)) once
        val result = eventSourcedTestKit.runCommand(Debit(txnId, entryCode, 1))
        result.events shouldBe Seq(Debited(txnId, entryCode, 2, 1, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 2
        result.stateOfType[DebitAccount].currentBalance shouldBe 1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 1
      }

      "accept DebitHold(uuid, 1) and have 2/0/2 balance" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 2, 0, 2, theTime)) once
        val result = eventSourcedTestKit.runCommand(DebitHold(txnId, entryCode, 1))
        result.events shouldBe Seq(DebitAuthorized(txnId, entryCode, 2, 2, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 2
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 2
      }

      "accept Capture(uuid, 1,0) and have 1/1/0 balance" in {
        given()
        val yesterday = DateUtils.now().minus(Duration.ofDays(1))

        stubMessenger expects(txnId, AccountingSuccessful(accountId, 1, 1, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(Post(txnId, entryCode, 1, 0, yesterday))
        result.events shouldBe Seq(DebitPosted(txnId, entryCode, 1, 1, 0, yesterday, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "reject Capture(uuid, 1,1) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        stubMessenger expects(txnId, AccountingFailed(accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)) once
        val result = eventSourcedTestKit.runCommand(Post(txnId, entryCode, 1, 1, theTime))
        result.hasNoEvents shouldBe true
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 1
      }

      "reject Capture(uuid, 2,0) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        stubMessenger expects(txnId, AccountingFailed(accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)) once
        val result = eventSourcedTestKit.runCommand(Post(txnId, entryCode, 2, 0, theTime))
        result.hasNoEvents shouldBe true
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 1
      }

      "accept Release(uuid, 1) and have 0/0/0 balance" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 0, 0, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(Release(txnId, entryCode, 1))
        result.events shouldBe Seq(Released(txnId, entryCode, 0, 0, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 0
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "reject Release(uuid, 2) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        stubMessenger expects(txnId, AccountingFailed(accountId, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)) once
        val result = eventSourcedTestKit.runCommand(Release(txnId, entryCode, 2))
        result.hasNoEvents shouldBe true
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 0
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 1
      }

    }

    "at initially 2/0/2 balance" must {
      def given(): Unit = {
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 2, 0, 2, theTime)) once
        val given2 = eventSourcedTestKit.runCommand(DebitHold(txnId, entryCode, 2))
      }

      "accept Capture(uuid, 1,1) and have 1/1/0 balance" in {
        given()
        val yesterday = DateUtils.now().minus(Duration.ofDays(1))

        stubMessenger expects(txnId, AccountingSuccessful(accountId, 1, 1, 0, theTime)) once
        val result = eventSourcedTestKit.runCommand(Post(txnId, entryCode, 1, 1, yesterday))
        result.events shouldBe Seq(DebitPosted(txnId, entryCode, 1, 1, 0, yesterday, theTime))
        result.stateOfType[DebitAccount].availableBalance shouldBe 1
        result.stateOfType[DebitAccount].currentBalance shouldBe 1
        result.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept Credit(uuid, 1), Capture(uuid, 2,0) and have 1/1/0 balance" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 1, -1, 2, theTime)) once
        val result1 = eventSourcedTestKit.runCommand(Credit(txnId, entryCode, 1))
        result1.events shouldBe Seq(Credited(txnId, entryCode, 1, -1, theTime), Overpaid(txnId, entryCode, theTime))
        result1.stateOfType[DebitAccount].availableBalance shouldBe 1
        result1.stateOfType[DebitAccount].currentBalance shouldBe -1
        result1.stateOfType[DebitAccount].authorizedBalance shouldBe 2

        val yesterday = DateUtils.now().minus(Duration.ofDays(1))

        stubMessenger expects(txnId, AccountingSuccessful(accountId, 1, 1, 0, theTime)) once
        val result2 = eventSourcedTestKit.runCommand(Post(txnId, entryCode, 2, 0, yesterday))
        result2.events shouldBe Seq(DebitPosted(txnId, entryCode, 1, 1, 0, yesterday, theTime))
        result2.stateOfType[DebitAccount].availableBalance shouldBe 1
        result2.stateOfType[DebitAccount].currentBalance shouldBe 1
        result2.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }

      "accept CreditAdjust(uuid, 2), Capture(uuid, 1,1) and have -1/-1/0 balance with Overpaid" in {
        given()
        stubMessenger expects(txnId, AccountingSuccessful(accountId, 0, -2, 2, theTime)) once
        val result1 = eventSourcedTestKit.runCommand(CreditAdjust(txnId, entryCode, 2))
        result1.events shouldBe Seq(Credited(txnId, entryCode, 0, -2, theTime), Overpaid(txnId, entryCode, theTime))
        result1.stateOfType[DebitAccount].availableBalance shouldBe 0
        result1.stateOfType[DebitAccount].currentBalance shouldBe -2
        result1.stateOfType[DebitAccount].authorizedBalance shouldBe 2

        val yesterday = DateUtils.now().minus(Duration.ofDays(1))

        stubMessenger expects(txnId, AccountingSuccessful(accountId, -1, -1, 0, theTime)) once
        val result2 = eventSourcedTestKit.runCommand(Post(txnId, entryCode, 1, 1, yesterday))
        result2.events shouldBe Seq(DebitPosted(txnId, entryCode, -1, -1, 0, yesterday, theTime), Overpaid(txnId, entryCode, theTime))
        result2.stateOfType[DebitAccount].availableBalance shouldBe -1
        result2.stateOfType[DebitAccount].currentBalance shouldBe -1
        result2.stateOfType[DebitAccount].authorizedBalance shouldBe 0
      }
    }
  }
}