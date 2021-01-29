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
  extends ScalaTestWithActorTestKit(config=ConfigFactory.parseString("""
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
  }

  "A Credit Account" must {
    "reject Debit(1) on balance 0 with INSUFFICIENT_FUNDS" in {
      val result = eventSourcedTestKit.runCommand[AdjustmentStatus](Debit(1, _))
      result.reply shouldBe AdjustmentFailed(LedgerError.INSUFFICIENT_FUNDS)
      result.hasNoEvents shouldBe true
      result.stateOfType[Active].availableBalance shouldBe 0
    }

    "accept Credit(1) on balance 0" in {
      val result = eventSourcedTestKit.runCommand[AdjustmentStatus](Credit(1, _))
      result.reply shouldBe AdjustmentSuccessful(1)
      result.event shouldBe Credited(1)
      result.stateOfType[Active].availableBalance shouldBe 1
    }

    "accept Debit(1) on balance 1" in {
      val result = eventSourcedTestKit.runCommand[AdjustmentStatus](Debit(1, _))
      result.reply shouldBe AdjustmentSuccessful(0)
      result.event shouldBe Debited(0)
      result.stateOfType[Active].availableBalance shouldBe 0
    }
    eventSourcedTestKit.clear()
  }
}