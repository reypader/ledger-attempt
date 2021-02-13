package io.openledger.domain.account

import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.typesafe.config.ConfigFactory
import io.openledger.domain.account.Account._
import io.openledger.domain.account.states.{AccountState, CreditAccount, DebitAccount, Ready}
import io.openledger.events.AccountEvent
import io.openledger.{AccountingMode, DateUtils, LedgerError}
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID
import scala.language.postfixOps

class ReadyAccountSpec
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

  override protected def afterEach(): Unit = {
    super.afterEach()
    eventSourcedTestKit.clear()
  }

  "A Ready Account" when {
    "opened" must {
      "become a DebitAccount" in {
        eventSourcedTestKit.runCommand(Open(AccountingMode.DEBIT, Set("A", "B")))

        val state = eventSourcedTestKit.runCommand[AccountState](Get)
        state.reply shouldBe DebitAccount(accountId, 0, 0, 0, Set("A", "B"))
      }

      "become a CreditAccount" in {
        eventSourcedTestKit.runCommand(Open(AccountingMode.CREDIT, Set("A", "B")))

        val state = eventSourcedTestKit.runCommand[AccountState](Get)
        state.reply shouldBe CreditAccount(accountId, 0, 0, 0, Set("A", "B"))
      }
    }
    "given any AccountingCommand" must {
      "reject Debit" in {
        val cmd = Debit(txnId, entryCode, 1)
        stubMessenger expects (txnId, AccountingFailed(
          cmd.hashCode(),
          accountId,
          LedgerError.UNSUPPORTED_ACCOUNT_OPERATION_ON_CURRENT_STATE
        )) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.hasNoEvents shouldBe true
        result.stateOfType[Ready].availableBalance shouldBe 0
        result.stateOfType[Ready].currentBalance shouldBe 0
        result.stateOfType[Ready].accountId shouldBe accountId
      }

      "reject Credit" in {
        val cmd = Credit(txnId, entryCode, 1)
        stubMessenger expects (txnId, AccountingFailed(
          cmd.hashCode(),
          accountId,
          LedgerError.UNSUPPORTED_ACCOUNT_OPERATION_ON_CURRENT_STATE
        )) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.hasNoEvents shouldBe true
        result.stateOfType[Ready].availableBalance shouldBe 0
        result.stateOfType[Ready].currentBalance shouldBe 0
        result.stateOfType[Ready].accountId shouldBe accountId
      }

      "reject DebitAdjust" in {
        val cmd = DebitAdjust(txnId, entryCode, 1)
        stubMessenger expects (txnId, AccountingFailed(
          cmd.hashCode(),
          accountId,
          LedgerError.UNSUPPORTED_ACCOUNT_OPERATION_ON_CURRENT_STATE
        )) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.hasNoEvents shouldBe true
        result.stateOfType[Ready].availableBalance shouldBe 0
        result.stateOfType[Ready].currentBalance shouldBe 0
        result.stateOfType[Ready].accountId shouldBe accountId
      }

      "reject CreditAdjust" in {
        val cmd = CreditAdjust(txnId, entryCode, 1)
        stubMessenger expects (txnId, AccountingFailed(
          cmd.hashCode(),
          accountId,
          LedgerError.UNSUPPORTED_ACCOUNT_OPERATION_ON_CURRENT_STATE
        )) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.hasNoEvents shouldBe true
        result.stateOfType[Ready].availableBalance shouldBe 0
        result.stateOfType[Ready].currentBalance shouldBe 0
        result.stateOfType[Ready].accountId shouldBe accountId
      }

      "reject DebitHold" in {
        val cmd = DebitHold(txnId, entryCode, 1)
        stubMessenger expects (txnId, AccountingFailed(
          cmd.hashCode(),
          accountId,
          LedgerError.UNSUPPORTED_ACCOUNT_OPERATION_ON_CURRENT_STATE
        )) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.hasNoEvents shouldBe true
        result.stateOfType[Ready].availableBalance shouldBe 0
        result.stateOfType[Ready].currentBalance shouldBe 0
        result.stateOfType[Ready].accountId shouldBe accountId
      }

      "reject DebitPost" in {
        val cmd = Post(txnId, entryCode, 1, 1, DateUtils.now())
        stubMessenger expects (txnId, AccountingFailed(
          cmd.hashCode(),
          accountId,
          LedgerError.UNSUPPORTED_ACCOUNT_OPERATION_ON_CURRENT_STATE
        )) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.hasNoEvents shouldBe true
        result.stateOfType[Ready].availableBalance shouldBe 0
        result.stateOfType[Ready].currentBalance shouldBe 0
        result.stateOfType[Ready].accountId shouldBe accountId
      }

      "reject Release" in {
        val cmd = Release(txnId, entryCode, 1)
        stubMessenger expects (txnId, AccountingFailed(
          cmd.hashCode(),
          accountId,
          LedgerError.UNSUPPORTED_ACCOUNT_OPERATION_ON_CURRENT_STATE
        )) once
        val result = eventSourcedTestKit.runCommand(cmd)
        result.hasNoEvents shouldBe true
        result.stateOfType[Ready].availableBalance shouldBe 0
        result.stateOfType[Ready].currentBalance shouldBe 0
        result.stateOfType[Ready].accountId shouldBe accountId
      }
    }
  }
}
