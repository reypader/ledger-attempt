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

class DebitAccountSpec
  extends ScalaTestWithActorTestKit(config = ConfigFactory.parseString(
    """
    akka.actor.serialization-bindings {
        "io.openledger.JsonSerializable" = jackson-json
    }
    """).withFallback(EventSourcedBehaviorTestKit.config))
    with AnyWordSpecLike
    with BeforeAndAfterEach
    with LogCapturing {

  private val eventSourcedTestKit = EventSourcedBehaviorTestKit[AccountCommand, AccountEvent, AccountState](system, Account(UUID.randomUUID(), AccountMode.DEBIT))

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    eventSourcedTestKit.clear()
  }

  "A Debit Account" must {
    "reject Credit(1) on available balance 0 with INSUFFICIENT_FUNDS" in {
      val result = eventSourcedTestKit.runCommand[AdjustmentStatus](Credit(1, _))
      result.reply shouldBe AdjustmentFailed(LedgerError.INSUFFICIENT_FUNDS)
      result.hasNoEvents shouldBe true
      result.stateOfType[Active].availableBalance shouldBe 0
      result.stateOfType[Active].currentBalance shouldBe 0
    }

    "accept Debit(1) on available balance 0" in {
      val result = eventSourcedTestKit.runCommand[AdjustmentStatus](Debit(1, _))
      result.reply shouldBe AdjustmentSuccessful(1, 1)
      result.event shouldBe Debited(1, 1)
      result.stateOfType[Active].availableBalance shouldBe 1
      result.stateOfType[Active].currentBalance shouldBe 1
    }

    "accept Credit(1) on available balance 1" in {
      val given = eventSourcedTestKit.runCommand[AdjustmentStatus](Debit(1, _))
      given.reply shouldBe AdjustmentSuccessful(1, 1)

      val result = eventSourcedTestKit.runCommand[AdjustmentStatus](Credit(1, _))
      result.reply shouldBe AdjustmentSuccessful(0, 0)
      result.event shouldBe Credited(0, 0)
      result.stateOfType[Active].availableBalance shouldBe 0
      result.stateOfType[Active].currentBalance shouldBe 0
    }

    "accept CreditHold(1) on available balance 2" in {
      val given = eventSourcedTestKit.runCommand[AdjustmentStatus](Debit(2, _))
      given.reply shouldBe AdjustmentSuccessful(2, 2)

      val result = eventSourcedTestKit.runCommand[AdjustmentStatus](CreditHold(1, _))
      result.reply shouldBe AdjustmentSuccessful(1, 2)
      result.event shouldBe CreditHeld(1)
      result.stateOfType[Active].availableBalance shouldBe 1
      result.stateOfType[Active].currentBalance shouldBe 2
    }

    "accept Credit(1) on available balance 0, current balance 1" in {
      val given1 = eventSourcedTestKit.runCommand[AdjustmentStatus](Debit(1, _))
      given1.reply shouldBe AdjustmentSuccessful(1, 1)

      val given2 = eventSourcedTestKit.runCommand[AdjustmentStatus](CreditHold(1, _))
      given2.reply shouldBe AdjustmentSuccessful(0, 1)

      val result = eventSourcedTestKit.runCommand[AdjustmentStatus](Credit(1, _))
      result.reply shouldBe AdjustmentFailed(LedgerError.INSUFFICIENT_FUNDS)
      result.hasNoEvents shouldBe true
      result.stateOfType[Active].availableBalance shouldBe 0
      result.stateOfType[Active].currentBalance shouldBe 1
    }

    "accept Debit(1) on available balance 0, current balance 1" in {
      val given1 = eventSourcedTestKit.runCommand[AdjustmentStatus](Debit(1, _))
      given1.reply shouldBe AdjustmentSuccessful(1, 1)

      val given2 = eventSourcedTestKit.runCommand[AdjustmentStatus](CreditHold(1, _))
      given2.reply shouldBe AdjustmentSuccessful(0, 1)

      val result = eventSourcedTestKit.runCommand[AdjustmentStatus](Debit(1, _))
      result.reply shouldBe AdjustmentSuccessful(1, 2)
      result.event shouldBe Debited(1, 2)
      result.stateOfType[Active].availableBalance shouldBe 1
      result.stateOfType[Active].currentBalance shouldBe 2
    }

    "accept CreditHold(1) on available balance 0, current balance 1" in {
      val given1 = eventSourcedTestKit.runCommand[AdjustmentStatus](Debit(1, _))
      given1.reply shouldBe AdjustmentSuccessful(1, 1)

      val given2 = eventSourcedTestKit.runCommand[AdjustmentStatus](CreditHold(1, _))
      given2.reply shouldBe AdjustmentSuccessful(0, 1)

      val result = eventSourcedTestKit.runCommand[AdjustmentStatus](CreditHold(1, _))
      result.reply shouldBe AdjustmentFailed(LedgerError.INSUFFICIENT_FUNDS)
      result.hasNoEvents shouldBe true
      result.stateOfType[Active].availableBalance shouldBe 0
      result.stateOfType[Active].currentBalance shouldBe 1
    }
  }
}