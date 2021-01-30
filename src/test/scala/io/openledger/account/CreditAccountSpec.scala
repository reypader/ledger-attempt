package io.openledger.account

import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.typesafe.config.ConfigFactory
import io.openledger.LedgerError
import io.openledger.account.Account._
import io.openledger.account.states.{AccountState, CreditAccount}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

class CreditAccountSpec
  extends ScalaTestWithActorTestKit(config = ConfigFactory.parseString(
    """
    akka.actor.serialization-bindings {
        "io.openledger.JsonSerializable" = jackson-json
    }
    """).withFallback(EventSourcedBehaviorTestKit.config))
    with AnyWordSpecLike
    with BeforeAndAfterEach
    with LogCapturing {

  private val eventSourcedTestKit = EventSourcedBehaviorTestKit[AccountCommand, AccountEvent, AccountState](system, Account(UUID.randomUUID().toString, AccountMode.CREDIT))
  private val uuid = UUID.randomUUID().toString

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    eventSourcedTestKit.clear()
  }

  "A Credit Account" when {

    "at initially 0/0/0 balance" must {
      def given(): Unit = {}

      "reject Debit(uuid, 1) with INSUFFICIENT_BALANCE" in {
        given()
        val result = eventSourcedTestKit.runCommand[AccountingStatus](Debit(uuid, 1, _))
        result.reply shouldBe AccountingFailed(uuid, LedgerError.INSUFFICIENT_BALANCE)
        result.hasNoEvents shouldBe true
        result.stateOfType[CreditAccount].availableBalance shouldBe 0
        result.stateOfType[CreditAccount].currentBalance shouldBe 0
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "accept Credit(uuid, 1) and have 1/1/0 balance" in {
        given()
        val result = eventSourcedTestKit.runCommand[AccountingStatus](Credit(uuid, 1, _))
        result.reply shouldBe AccountingSuccessful(uuid, 1, 1, 0)
        result.events shouldBe Seq(Credited(uuid, 1, 1))
        result.stateOfType[CreditAccount].availableBalance shouldBe 1
        result.stateOfType[CreditAccount].currentBalance shouldBe 1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "reject Hold(uuid, 1) with INSUFFICIENT_BALANCE" in {
        given()
        val result = eventSourcedTestKit.runCommand[AccountingStatus](Hold(uuid, 1, _))
        result.reply shouldBe AccountingFailed(uuid, LedgerError.INSUFFICIENT_BALANCE)
        result.hasNoEvents shouldBe true
        result.stateOfType[CreditAccount].availableBalance shouldBe 0
        result.stateOfType[CreditAccount].currentBalance shouldBe 0
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "reject Capture(uuid, 1,0) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        val result = eventSourcedTestKit.runCommand[AccountingStatus](Capture(uuid, 1, 0, _))
        result.reply shouldBe AccountingFailed(uuid, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)
        result.hasNoEvents shouldBe true
        result.stateOfType[CreditAccount].availableBalance shouldBe 0
        result.stateOfType[CreditAccount].currentBalance shouldBe 0
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "reject Release(uuid, 1) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        val result = eventSourcedTestKit.runCommand[AccountingStatus](Release(uuid, 1, _))
        result.reply shouldBe AccountingFailed(uuid, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)
        result.hasNoEvents shouldBe true
        result.stateOfType[CreditAccount].availableBalance shouldBe 0
        result.stateOfType[CreditAccount].currentBalance shouldBe 0
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "accept DebitAdjust(uuid, 1) and have -1/-1/0 balance with Overdrawn" in {
        given()
        val result = eventSourcedTestKit.runCommand[AccountingStatus](DebitAdjust(uuid, 1, _))
        result.reply shouldBe AccountingSuccessful(uuid, -1, -1, 0)
        result.events shouldBe Seq(Debited(uuid, -1, -1), Overdrawn(uuid))
        result.stateOfType[CreditAccount].availableBalance shouldBe -1
        result.stateOfType[CreditAccount].currentBalance shouldBe -1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "accept CreditAdjust(uuid, 1) and have 1/1/0 balance" in {
        given()
        val result = eventSourcedTestKit.runCommand[AccountingStatus](CreditAdjust(uuid, 1, _))
        result.reply shouldBe AccountingSuccessful(uuid, 1, 1, 0)
        result.events shouldBe Seq(Credited(uuid, 1, 1))
        result.stateOfType[CreditAccount].availableBalance shouldBe 1
        result.stateOfType[CreditAccount].currentBalance shouldBe 1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }
    }

    "at initially 1/1/0 balance" must {
      def given(): Unit = {
        val given = eventSourcedTestKit.runCommand[AccountingStatus](Credit(uuid, 1, _))
        given.reply shouldBe AccountingSuccessful(uuid, 1, 1, 0)
      }

      "accept Debit(uuid, 1) and have 0/0/0 balance" in {
        given()
        val result = eventSourcedTestKit.runCommand[AccountingStatus](Debit(uuid, 1, _))
        result.reply shouldBe AccountingSuccessful(uuid, 0, 0, 0)
        result.events shouldBe Seq(Debited(uuid, 0, 0))
        result.stateOfType[CreditAccount].availableBalance shouldBe 0
        result.stateOfType[CreditAccount].currentBalance shouldBe 0
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "accept Credit(uuid, 1) and have 2/2/0 balance" in {
        given()
        val result = eventSourcedTestKit.runCommand[AccountingStatus](Credit(uuid, 1, _))
        result.reply shouldBe AccountingSuccessful(uuid, 2, 2, 0)
        result.events shouldBe Seq(Credited(uuid, 2, 2))
        result.stateOfType[CreditAccount].availableBalance shouldBe 2
        result.stateOfType[CreditAccount].currentBalance shouldBe 2
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "accept Hold(uuid, 1) and have 0/1/1 balance" in {
        given()
        val result = eventSourcedTestKit.runCommand[AccountingStatus](Hold(uuid, 1, _))
        result.reply shouldBe AccountingSuccessful(uuid, 0, 1, 1)
        result.events shouldBe Seq(Authorized(uuid, 0, 1))
        result.stateOfType[CreditAccount].availableBalance shouldBe 0
        result.stateOfType[CreditAccount].currentBalance shouldBe 1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 1
      }

      "reject Hold(uuid, 2) with INSUFFICIENT_BALANCE" in {
        given()
        val result = eventSourcedTestKit.runCommand[AccountingStatus](Hold(uuid, 2, _))
        result.reply shouldBe AccountingFailed(uuid, LedgerError.INSUFFICIENT_BALANCE)
        result.hasNoEvents shouldBe true
        result.stateOfType[CreditAccount].availableBalance shouldBe 1
        result.stateOfType[CreditAccount].currentBalance shouldBe 1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "reject Capture(uuid, 1,0) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        val result = eventSourcedTestKit.runCommand[AccountingStatus](Capture(uuid, 1, 0, _))
        result.reply shouldBe AccountingFailed(uuid, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)
        result.hasNoEvents shouldBe true
        result.stateOfType[CreditAccount].availableBalance shouldBe 1
        result.stateOfType[CreditAccount].currentBalance shouldBe 1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "reject Release(uuid, 1) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        val result = eventSourcedTestKit.runCommand[AccountingStatus](Release(uuid, 1, _))
        result.reply shouldBe AccountingFailed(uuid, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)
        result.hasNoEvents shouldBe true
        result.stateOfType[CreditAccount].availableBalance shouldBe 1
        result.stateOfType[CreditAccount].currentBalance shouldBe 1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "accept DebitAdjust(uuid, 1) and have 0/0/0 balance" in {
        given()
        val result = eventSourcedTestKit.runCommand[AccountingStatus](DebitAdjust(uuid, 1, _))
        result.reply shouldBe AccountingSuccessful(uuid, 0, 0, 0)
        result.events shouldBe Seq(Debited(uuid, 0, 0))
        result.stateOfType[CreditAccount].availableBalance shouldBe 0
        result.stateOfType[CreditAccount].currentBalance shouldBe 0
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "accept CreditAdjust(uuid, 1) and have 2/2/0 balance" in {
        given()
        val result = eventSourcedTestKit.runCommand[AccountingStatus](CreditAdjust(uuid, 1, _))
        result.reply shouldBe AccountingSuccessful(uuid, 2, 2, 0)
        result.events shouldBe Seq(Credited(uuid, 2, 2))
        result.stateOfType[CreditAccount].availableBalance shouldBe 2
        result.stateOfType[CreditAccount].currentBalance shouldBe 2
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

    }

    "at initially 0/1/1 balance" must {
      def given(): Unit = {
        val given1 = eventSourcedTestKit.runCommand[AccountingStatus](Credit(uuid, 1, _))
        given1.reply shouldBe AccountingSuccessful(uuid, 1, 1, 0)

        val given2 = eventSourcedTestKit.runCommand[AccountingStatus](Hold(uuid, 1, _))
        given2.reply shouldBe AccountingSuccessful(uuid, 0, 1, 1)
      }

      "reject Debit(uuid, 1) with INSUFFICIENT_BALANCE" in {
        given()
        val result = eventSourcedTestKit.runCommand[AccountingStatus](Debit(uuid, 1, _))
        result.reply shouldBe AccountingFailed(uuid, LedgerError.INSUFFICIENT_BALANCE)
        result.hasNoEvents shouldBe true
        result.stateOfType[CreditAccount].availableBalance shouldBe 0
        result.stateOfType[CreditAccount].currentBalance shouldBe 1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 1
      }

      "accept Credit(uuid, 1) and have 1/2/1 balance" in {
        given()
        val result = eventSourcedTestKit.runCommand[AccountingStatus](Credit(uuid, 1, _))
        result.reply shouldBe AccountingSuccessful(uuid, 1, 2, 1)
        result.events shouldBe Seq(Credited(uuid, 1, 2))
        result.stateOfType[CreditAccount].availableBalance shouldBe 1
        result.stateOfType[CreditAccount].currentBalance shouldBe 2
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 1
      }

      "reject Hold(uuid, 1) with INSUFFICIENT_BALANCE" in {
        given()
        val result = eventSourcedTestKit.runCommand[AccountingStatus](Hold(uuid, 1, _))
        result.reply shouldBe AccountingFailed(uuid, LedgerError.INSUFFICIENT_BALANCE)
        result.hasNoEvents shouldBe true
        result.stateOfType[CreditAccount].availableBalance shouldBe 0
        result.stateOfType[CreditAccount].currentBalance shouldBe 1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 1
      }

      "accept Capture(uuid, 1,0) and have 0/0/0 balance" in {
        given()
        val result = eventSourcedTestKit.runCommand[AccountingStatus](Capture(uuid, 1, 0, _))
        result.reply shouldBe AccountingSuccessful(uuid, 0, 0, 0)
        result.events shouldBe Seq(Captured(uuid, 0, 0, 0))
        result.stateOfType[CreditAccount].availableBalance shouldBe 0
        result.stateOfType[CreditAccount].currentBalance shouldBe 0
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "reject Capture(uuid, 1,1) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        val result = eventSourcedTestKit.runCommand[AccountingStatus](Capture(uuid, 1, 1, _))
        result.reply shouldBe AccountingFailed(uuid, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)
        result.hasNoEvents shouldBe true
        result.stateOfType[CreditAccount].availableBalance shouldBe 0
        result.stateOfType[CreditAccount].currentBalance shouldBe 1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 1
      }

      "reject Capture(uuid, 2,0) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        val result = eventSourcedTestKit.runCommand[AccountingStatus](Capture(uuid, 2, 0, _))
        result.reply shouldBe AccountingFailed(uuid, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)
        result.hasNoEvents shouldBe true
        result.stateOfType[CreditAccount].availableBalance shouldBe 0
        result.stateOfType[CreditAccount].currentBalance shouldBe 1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 1
      }

      "accept Release(uuid, 1) and have 1/1/0 balance" in {
        given()
        val result = eventSourcedTestKit.runCommand[AccountingStatus](Release(uuid, 1, _))
        result.reply shouldBe AccountingSuccessful(uuid, 1, 1, 0)
        result.events shouldBe Seq(Released(uuid, 1, 0))
        result.stateOfType[CreditAccount].availableBalance shouldBe 1
        result.stateOfType[CreditAccount].currentBalance shouldBe 1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "reject Release(uuid, 2) with INSUFFICIENT_AUTHORIZED_BALANCE" in {
        given()
        val result = eventSourcedTestKit.runCommand[AccountingStatus](Release(uuid, 2, _))
        result.reply shouldBe AccountingFailed(uuid, LedgerError.INSUFFICIENT_AUTHORIZED_BALANCE)
        result.hasNoEvents shouldBe true
        result.stateOfType[CreditAccount].availableBalance shouldBe 0
        result.stateOfType[CreditAccount].currentBalance shouldBe 1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 1
      }

    }

    "at initially 0/2/2 balance" must {
      def given(): Unit = {
        val given1 = eventSourcedTestKit.runCommand[AccountingStatus](Credit(uuid, 2, _))
        given1.reply shouldBe AccountingSuccessful(uuid, 2, 2, 0)

        val given2 = eventSourcedTestKit.runCommand[AccountingStatus](Hold(uuid, 2, _))
        given2.reply shouldBe AccountingSuccessful(uuid, 0, 2, 2)
      }

      "accept Capture(uuid, 1,1) and have 1/1/0 balance" in {
        given()
        val result = eventSourcedTestKit.runCommand[AccountingStatus](Capture(uuid, 1, 1, _))
        result.reply shouldBe AccountingSuccessful(uuid, 1, 1, 0)
        result.events shouldBe Seq(Captured(uuid, 1, 1, 0))
        result.stateOfType[CreditAccount].availableBalance shouldBe 1
        result.stateOfType[CreditAccount].currentBalance shouldBe 1
        result.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }

      "accept DebitAdjust(uuid, 1), Capture(uuid, 2,0) and have -1/-1/0 balance" in {
        given()
        val result1 = eventSourcedTestKit.runCommand[AccountingStatus](DebitAdjust(uuid, 1, _))
        result1.reply shouldBe AccountingSuccessful(uuid, -1, 1, 2)
        result1.events shouldBe Seq(Debited(uuid, -1, 1), Overdrawn(uuid))
        result1.stateOfType[CreditAccount].availableBalance shouldBe -1
        result1.stateOfType[CreditAccount].currentBalance shouldBe 1
        result1.stateOfType[CreditAccount].authorizedBalance shouldBe 2

        val result2 = eventSourcedTestKit.runCommand[AccountingStatus](Capture(uuid, 2, 0, _))
        result2.reply shouldBe AccountingSuccessful(uuid, -1, -1, 0)
        result2.events shouldBe Seq(Captured(uuid, -1, -1, 0), Overdrawn(uuid))
        result2.stateOfType[CreditAccount].availableBalance shouldBe -1
        result2.stateOfType[CreditAccount].currentBalance shouldBe -1
        result2.stateOfType[CreditAccount].authorizedBalance shouldBe 0
      }
    }
  }
}