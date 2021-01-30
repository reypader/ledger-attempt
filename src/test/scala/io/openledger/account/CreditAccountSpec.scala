package io.openledger.account

import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.typesafe.config.ConfigFactory
import io.openledger.LedgerError
import io.openledger.account.Account._
import io.openledger.account.states.{AccountState, Active}
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

  private val eventSourcedTestKit = EventSourcedBehaviorTestKit[AccountCommand, AccountEvent, AccountState](system, Account(UUID.randomUUID(), AccountMode.CREDIT))

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    eventSourcedTestKit.clear()
  }

  "A Credit Account" when {

    "at initially 0/0/0 balance" must {
      def given(): Unit = {}

      "reject Debit(1) with INSUFFICIENT_FUNDS" in {
        given()
        val result = eventSourcedTestKit.runCommand[AdjustmentStatus](Debit(1, _))
        result.reply shouldBe AdjustmentFailed(LedgerError.INSUFFICIENT_FUNDS)
        result.hasNoEvents shouldBe true
        result.stateOfType[Active].availableBalance shouldBe 0
        result.stateOfType[Active].currentBalance shouldBe 0
        result.stateOfType[Active].reservedBalance shouldBe 0
      }

      "reject DebitHold(1) with INSUFFICIENT_FUNDS" in {
        given()
        val result = eventSourcedTestKit.runCommand[AdjustmentStatus](DebitHold(1, _))
        result.reply shouldBe AdjustmentFailed(LedgerError.INSUFFICIENT_FUNDS)
        result.hasNoEvents shouldBe true
        result.stateOfType[Active].availableBalance shouldBe 0
        result.stateOfType[Active].currentBalance shouldBe 0
        result.stateOfType[Active].reservedBalance shouldBe 0
      }

      "reject CreditHold(1) with UNSUPPORTED_CREDIT_HOLD" in {
        given()
        val result = eventSourcedTestKit.runCommand[AdjustmentStatus](CreditHold(1, _))
        result.reply shouldBe AdjustmentFailed(LedgerError.UNSUPPORTED_CREDIT_HOLD)
        result.hasNoEvents shouldBe true
        result.stateOfType[Active].availableBalance shouldBe 0
        result.stateOfType[Active].currentBalance shouldBe 0
        result.stateOfType[Active].reservedBalance shouldBe 0
      }

      "accept Credit(1) and have 1/1/0 balance" in {
        given()
        val result = eventSourcedTestKit.runCommand[AdjustmentStatus](Credit(1, _))
        result.reply shouldBe AdjustmentSuccessful(1, 1, 0)
        result.event shouldBe Credited(1, 1)
        result.stateOfType[Active].availableBalance shouldBe 1
        result.stateOfType[Active].currentBalance shouldBe 1
        result.stateOfType[Active].reservedBalance shouldBe 0
      }
    }

    "at initially 1/1/0 balance" must {
      def given(): Unit = {
        val given = eventSourcedTestKit.runCommand[AdjustmentStatus](Credit(1, _))
        given.reply shouldBe AdjustmentSuccessful(1, 1, 0)
      }

      "accept Debit(1) and have 0/0/0 balance" in {
        given()
        val result = eventSourcedTestKit.runCommand[AdjustmentStatus](Debit(1, _))
        result.reply shouldBe AdjustmentSuccessful(0, 0, 0)
        result.event shouldBe Debited(0, 0)
        result.stateOfType[Active].availableBalance shouldBe 0
        result.stateOfType[Active].currentBalance shouldBe 0
        result.stateOfType[Active].reservedBalance shouldBe 0
      }

      "accept DebitHold(1) and have 0/1/1 balance" in {
        given()
        val result = eventSourcedTestKit.runCommand[AdjustmentStatus](DebitHold(1, _))
        result.reply shouldBe AdjustmentSuccessful(0, 1, 1)
        result.event shouldBe DebitHeld(0, 1)
        result.stateOfType[Active].availableBalance shouldBe 0
        result.stateOfType[Active].currentBalance shouldBe 1
        result.stateOfType[Active].reservedBalance shouldBe 1
      }

      "reject DebitHold(2) with INSUFFICIENT_FUNDS" in {
        given()
        val result = eventSourcedTestKit.runCommand[AdjustmentStatus](DebitHold(2, _))
        result.reply shouldBe AdjustmentFailed(LedgerError.INSUFFICIENT_FUNDS)
        result.hasNoEvents shouldBe true
        result.stateOfType[Active].availableBalance shouldBe 1
        result.stateOfType[Active].currentBalance shouldBe 1
        result.stateOfType[Active].reservedBalance shouldBe 0
      }

      "accept Credit(1) and have 2/2/0 balance" in {
        given()
        val result = eventSourcedTestKit.runCommand[AdjustmentStatus](Credit(1, _))
        result.reply shouldBe AdjustmentSuccessful(2, 2, 0)
        result.event shouldBe Credited(2, 2)
        result.stateOfType[Active].availableBalance shouldBe 2
        result.stateOfType[Active].currentBalance shouldBe 2
        result.stateOfType[Active].reservedBalance shouldBe 0
      }
    }

    "at initially 0/1/1 balance" must {
      def given(): Unit = {
        val given1 = eventSourcedTestKit.runCommand[AdjustmentStatus](Credit(1, _))
        given1.reply shouldBe AdjustmentSuccessful(1, 1, 0)

        val given2 = eventSourcedTestKit.runCommand[AdjustmentStatus](DebitHold(1, _))
        given2.reply shouldBe AdjustmentSuccessful(0, 1, 1)
      }

      "reject Debit(1) with INSUFFICIENT_FUNDS" in {
        given()
        val result = eventSourcedTestKit.runCommand[AdjustmentStatus](Debit(1, _))
        result.reply shouldBe AdjustmentFailed(LedgerError.INSUFFICIENT_FUNDS)
        result.hasNoEvents shouldBe true
        result.stateOfType[Active].availableBalance shouldBe 0
        result.stateOfType[Active].currentBalance shouldBe 1
        result.stateOfType[Active].reservedBalance shouldBe 1
      }

      "reject DebitHold(1) with INSUFFICIENT_FUNDS" in {
        given()
        val result = eventSourcedTestKit.runCommand[AdjustmentStatus](DebitHold(1, _))
        result.reply shouldBe AdjustmentFailed(LedgerError.INSUFFICIENT_FUNDS)
        result.hasNoEvents shouldBe true
        result.stateOfType[Active].availableBalance shouldBe 0
        result.stateOfType[Active].currentBalance shouldBe 1
        result.stateOfType[Active].reservedBalance shouldBe 1
      }

      "accept Credit(1) and have 1/2/1 balance" in {
        given()
        val result = eventSourcedTestKit.runCommand[AdjustmentStatus](Credit(1, _))
        result.reply shouldBe AdjustmentSuccessful(1, 2, 1)
        result.event shouldBe Credited(1, 2)
        result.stateOfType[Active].availableBalance shouldBe 1
        result.stateOfType[Active].currentBalance shouldBe 2
        result.stateOfType[Active].reservedBalance shouldBe 1
      }
    }

    "at initially 2/2/0 balance" must {
      def given(): Unit = {
        val given = eventSourcedTestKit.runCommand[AdjustmentStatus](Credit(2, _))
        given.reply shouldBe AdjustmentSuccessful(2, 2, 0)
      }

      "accept DebitHold(1) and have 1/2/1 balance" in {
        given()
        val result = eventSourcedTestKit.runCommand[AdjustmentStatus](DebitHold(1, _))
        result.reply shouldBe AdjustmentSuccessful(1, 2, 1)
        result.event shouldBe DebitHeld(1, 1)
        result.stateOfType[Active].availableBalance shouldBe 1
        result.stateOfType[Active].currentBalance shouldBe 2
        result.stateOfType[Active].reservedBalance shouldBe 1
      }
    }
  }
}