package io.openledger

import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.typesafe.config.ConfigFactory
import io.openledger.domain.account.Account
import io.openledger.domain.account.Account.{Get, _}
import io.openledger.domain.account.states.{AccountState, CreditAccount, DebitAccount}
import io.openledger.domain.entry.Entry
import io.openledger.domain.entry.Entry.{apply => _, _}
import io.openledger.domain.entry.states.EntryState
import io.openledger.events.{AccountEvent, EntryEvent}
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class LedgerSpec
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

  private val logger: Logger = LoggerFactory.getLogger(classOf[LedgerSpec])

  private val ackProbe = testKit.createTestProbe[TxnAck]
  private val txnA = "TXN-A"
  private val entryA = "ENTRY-A"
  private val txnB = "TXN-B"
  private val entryB = "ENTRY-B"
  private val accountA = "ACCOUNT-A"
  private val accountB = "ACCOUNT-B"
  private val accountATestKit = EventSourcedBehaviorTestKit[AccountCommand, AccountEvent, AccountState](
    system,
    Account(accountA)(stubEntryMessenger, () => DateUtils.now())
  )
  private val accountBTestKit = EventSourcedBehaviorTestKit[AccountCommand, AccountEvent, AccountState](
    system,
    Account(accountB)(stubEntryMessenger, () => DateUtils.now())
  )
  private val entryATestKit = EventSourcedBehaviorTestKit[EntryCommand, EntryEvent, EntryState](
    system,
    Entry(txnA)(stubAccountMessenger, stubResultMessenger)
  )
  private val entryBTestKit = EventSourcedBehaviorTestKit[EntryCommand, EntryEvent, EntryState](
    system,
    Entry(txnB)(stubAccountMessenger, stubResultMessenger)
  )
  private val resultProbe = testKit.createTestProbe[EntryResult]

  def stubAccountMessenger(accountId: String, message: AccountingCommand): Unit = {
    if (accountId == accountA) {
      akka.pattern.after(10.millisecond) {
        Future {
          logger.info(s"Sending to Account A $message")
          accountATestKit.runCommand(message)
        }
      }
    } else if (accountId == accountB) {
      akka.pattern.after(10.millisecond) {
        Future {
          logger.info(s"Sending to Account B $message")
          accountBTestKit.runCommand(message)
        }
      }
    } else {
      logger.info(s"Account sending Ignored")
    }
  }

  def stubResultMessenger(message: EntryResult): Unit = {
    logger.info(s"Sending Result to Probe $message")
    resultProbe ! message
  }

  def stubEntryMessenger(entryId: String, message: AccountingStatus): Unit = {
    if (entryId == txnA) {
      logger.info(s"Sending to Entry A $message")
      val sendThis = message match {
        case AccountingSuccessful(cmd, accountId, availableBalance, currentBalance, _, timestamp) =>
          AcceptAccounting(cmd, accountId, ResultingBalance(availableBalance, currentBalance), timestamp)
        case AccountingFailed(cmd, accountId, code) =>
          RejectAccounting(cmd, accountId, code)
      }
      akka.pattern.after(10.millisecond) {
        Future {
          logger.info(s"Send to Entry A $sendThis")
          entryATestKit.runCommand(sendThis)
        }
      }

    } else if (entryId == txnB) {
      logger.info(s"Sending to Entry B $message")
      val sendThis = message match {
        case AccountingSuccessful(cmd, accountId, availableBalance, currentBalance, _, timestamp) =>
          AcceptAccounting(cmd, accountId, ResultingBalance(availableBalance, currentBalance), timestamp)
        case AccountingFailed(cmd, accountId, code) =>
          RejectAccounting(cmd, accountId, code)
      }
      akka.pattern.after(10.millisecond) {
        Future {
          logger.info(s"Send to Entry B $sendThis")
          entryBTestKit.runCommand(sendThis)
        }
      }

    } else {
      logger.info(s"Entry sending Ignored")
    }
  }

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    entryATestKit.clear()
    entryBTestKit.clear()
    accountATestKit.clear()
    accountBTestKit.clear()
  }

  "Two Debit Accounts with A = 100/100/0, B = 100/100/0 balance" when {
    def accountSetup(): Unit = {
      accountATestKit.runCommand(Open(AccountingMode.DEBIT, Set.empty))
      accountBTestKit.runCommand(Open(AccountingMode.DEBIT, Set.empty))
      accountATestKit.runCommand(Debit("SETUP-A", "SETUP-ENTRY", 100))
      accountBTestKit.runCommand(Debit("SETUP-B", "SETUP-ENTRY", 100))
    }

    "a simple transfer (10) is made" must {
      def entrySetup(): Unit = {
        accountSetup()
        entryATestKit.runCommand(Begin(entryA, accountA, accountB, 10, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntrySuccessful]
      }

      "result in A = 110/110/0, B = 90/90/0" in {
        entrySetup()
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe DebitAccount(accountA, 110, 110, 0, Set.empty)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe DebitAccount(accountB, 90, 90, 0, Set.empty)
      }

      "be reversed back to A = 100/100/0, B = 100/100/0 balance" in {
        entrySetup()
        entryATestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntryReversed]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe DebitAccount(accountA, 100, 100, 0, Set.empty)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe DebitAccount(accountB, 100, 100, 0, Set.empty)
      }
    }

    "an authorized transfer (10) is made" must {
      def entrySetup(): Unit = {
        accountSetup()
        entryATestKit.runCommand(Begin(entryA, accountA, accountB, 10, ackProbe.ref, authOnly = true))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntryPending]
      }

      "result in A = 110/100/10, B = 100/100/0" in {
        entrySetup()
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe DebitAccount(accountA, 110, 100, 10, Set.empty)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe DebitAccount(accountB, 100, 100, 0, Set.empty)
      }

      "be reversed back to A = 100/100/0, B = 100/100/0 balance" in {
        entrySetup()
        entryATestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntryReversed]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe DebitAccount(accountA, 100, 100, 0, Set.empty)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe DebitAccount(accountB, 100, 100, 0, Set.empty)
      }

      "be partially captured (5) to A = 105/105/0, B = 95/95/0 balance" in {
        entrySetup()
        entryATestKit.runCommand(Capture(5, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntrySuccessful]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe DebitAccount(accountA, 105, 105, 0, Set.empty)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe DebitAccount(accountB, 95, 95, 0, Set.empty)
      }

      "not react to over-capture and then partially captured (5) to A = 105/105/0, B = 95/95/0 balance" in {
        entrySetup()

        entryATestKit.runCommand(Capture(11, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[CaptureRejected]

        entryATestKit.runCommand(Capture(5, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntrySuccessful]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe DebitAccount(accountA, 105, 105, 0, Set.empty)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe DebitAccount(accountB, 95, 95, 0, Set.empty)
      }

      "allow another authorize (15) followed by a partial capture (5) to A = 120/105/15, B = 95/95/0 balance" in {
        entrySetup()

        entryBTestKit.runCommand(Begin(entryB, accountA, accountB, 15, ackProbe.ref, authOnly = true))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntryPending]

        entryATestKit.runCommand(Capture(5, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntrySuccessful]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe DebitAccount(accountA, 120, 105, 15, Set.empty)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe DebitAccount(accountB, 95, 95, 0, Set.empty)
      }

      "allow another authorize (15), reversed, followed by a partial capture (5) to A = 105/105/0, B = 95/95/0 balance" in {
        entrySetup()

        entryBTestKit.runCommand(Begin(entryB, accountA, accountB, 15, ackProbe.ref, authOnly = true))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntryPending]

        entryBTestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntryReversed]

        entryATestKit.runCommand(Capture(5, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntrySuccessful]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe DebitAccount(accountA, 105, 105, 0, Set.empty)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe DebitAccount(accountB, 95, 95, 0, Set.empty)
      }

      "allow another transfer (15) followed by a partial capture (5) to A = 120/120/0, B = 80/80/0 balance" in {
        entrySetup()

        entryBTestKit.runCommand(Begin(entryB, accountA, accountB, 15, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntrySuccessful]

        entryATestKit.runCommand(Capture(5, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntrySuccessful]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe DebitAccount(accountA, 120, 120, 0, Set.empty)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe DebitAccount(accountB, 80, 80, 0, Set.empty)
      }

      "allow another transfer (15), reversed, followed by a partial capture (5) to A = 105/105/0, B = 95/95/0 balance" in {
        entrySetup()

        entryBTestKit.runCommand(Begin(entryB, accountA, accountB, 15, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntrySuccessful]

        entryBTestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntryReversed]

        entryATestKit.runCommand(Capture(5, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntrySuccessful]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe DebitAccount(accountA, 105, 105, 0, Set.empty)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe DebitAccount(accountB, 95, 95, 0, Set.empty)
      }
    }
  }

  "Two Debit Accounts with A = 0/0/0, B = 0/0/0 balance" when {
    def accountSetup(): Unit = {
      accountATestKit.runCommand(Open(AccountingMode.DEBIT, Set.empty))
      accountBTestKit.runCommand(Open(AccountingMode.DEBIT, Set.empty))
    }

    "a simple transfer (10) is made" must {
      def entrySetup(): Unit = {
        accountSetup()
        entryATestKit.runCommand(Begin(entryA, accountA, accountB, 10, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntryFailed]
      }

      "result in INSUFFICIENT_BALANCE for B, and reverse to A = 0/0/0, B = 0/0/0" in {
        entrySetup()
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe DebitAccount(accountA, 0, 0, 0, Set.empty)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe DebitAccount(accountB, 0, 0, 0, Set.empty)
      }
    }

    "an authorized transfer (10) is made" must {
      def entrySetup(): Unit = {
        accountSetup()
        entryATestKit.runCommand(Begin(entryA, accountA, accountB, 10, ackProbe.ref, authOnly = true))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntryPending]
      }

      //TODO: Not sure if this is a proper behavior or should remain Pending
      "capture result in INSUFFICIENT_BALANCE for B, and reverse to A = 0/0/0, B = 0/0/0" in {
        entrySetup()

        entryATestKit.runCommand(Capture(1, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntryFailed]

        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe DebitAccount(accountA, 0, 0, 0, Set.empty)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe DebitAccount(accountB, 0, 0, 0, Set.empty)
      }

    }
  }

  "Two Credit Accounts with A = 100/100/0, B = 100/100/0 balance" when {
    def accountSetup(): Unit = {
      accountATestKit.runCommand(Open(AccountingMode.CREDIT, Set.empty))
      accountBTestKit.runCommand(Open(AccountingMode.CREDIT, Set.empty))
      accountATestKit.runCommand(Credit("SETUP-A", "SETUP-ENTRY", 100))
      accountBTestKit.runCommand(Credit("SETUP-B", "SETUP-ENTRY", 100))
    }

    "a simple transfer (10) is made" must {
      def entrySetup(): Unit = {
        accountSetup()
        entryATestKit.runCommand(Begin(entryA, accountA, accountB, 10, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntrySuccessful]
      }

      "result in A = 90/90/0, B = 110/110/0" in {
        entrySetup()
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe CreditAccount(accountA, 90, 90, 0, Set.empty)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe CreditAccount(accountB, 110, 110, 0, Set.empty)
      }

      "be reversed back to A = 100/100/0, B = 100/100/0 balance" in {
        entrySetup()
        entryATestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntryReversed]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe CreditAccount(accountA, 100, 100, 0, Set.empty)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe CreditAccount(accountB, 100, 100, 0, Set.empty)
      }
    }

    "an authorized transfer (10) is made" must {
      def entrySetup(): Unit = {
        accountSetup()
        entryATestKit.runCommand(Begin(entryA, accountA, accountB, 10, ackProbe.ref, authOnly = true))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntryPending]
      }

      "result in A = 90/100/10, B = 100/100/0" in {
        entrySetup()
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe CreditAccount(accountA, 90, 100, 10, Set.empty)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe CreditAccount(accountB, 100, 100, 0, Set.empty)
      }

      "be reversed back to A = 100/100/0, B = 100/100/0 balance" in {
        entrySetup()
        entryATestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntryReversed]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe CreditAccount(accountA, 100, 100, 0, Set.empty)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe CreditAccount(accountB, 100, 100, 0, Set.empty)
      }

      "be partially captured (5) to A = 95/95/0, B = 105/105/0 balance" in {
        entrySetup()
        entryATestKit.runCommand(Capture(5, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntrySuccessful]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe CreditAccount(accountA, 95, 95, 0, Set.empty)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe CreditAccount(accountB, 105, 105, 0, Set.empty)
      }

      "not react to over-capture and then partially captured (5) to A = 95/95/0, B = 105/105/0 balance" in {
        entrySetup()

        entryATestKit.runCommand(Capture(11, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[CaptureRejected]

        entryATestKit.runCommand(Capture(5, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntrySuccessful]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe CreditAccount(accountA, 95, 95, 0, Set.empty)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe CreditAccount(accountB, 105, 105, 0, Set.empty)
      }

      "allow another authorize (15) followed by a partial capture (5) to A = 80/95/15, B = 105/105/0 balance" in {
        entrySetup()

        entryBTestKit.runCommand(Begin(entryB, accountA, accountB, 15, ackProbe.ref, authOnly = true))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntryPending]

        entryATestKit.runCommand(Capture(5, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntrySuccessful]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe CreditAccount(accountA, 80, 95, 15, Set.empty)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe CreditAccount(accountB, 105, 105, 0, Set.empty)
      }

      "allow another authorize (15), reversed, followed by a partial capture (5) to A = 95/95/0, B = 105/105/0 balance" in {
        entrySetup()

        entryBTestKit.runCommand(Begin(entryB, accountA, accountB, 15, ackProbe.ref, authOnly = true))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntryPending]

        entryBTestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntryReversed]

        entryATestKit.runCommand(Capture(5, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntrySuccessful]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe CreditAccount(accountA, 95, 95, 0, Set.empty)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe CreditAccount(accountB, 105, 105, 0, Set.empty)
      }

      "allow another transfer (15) followed by a partial capture (5) to A = 80/80/0, B = 120/120/0 balance" in {
        entrySetup()

        entryBTestKit.runCommand(Begin(entryB, accountA, accountB, 15, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntrySuccessful]

        entryATestKit.runCommand(Capture(5, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntrySuccessful]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe CreditAccount(accountA, 80, 80, 0, Set.empty)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe CreditAccount(accountB, 120, 120, 0, Set.empty)
      }

      "allow another transfer (15), reversed, followed by a partial capture (5) to A = 95/95/0, B = 105/105/0 balance" in {
        entrySetup()

        entryBTestKit.runCommand(Begin(entryB, accountA, accountB, 15, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntrySuccessful]

        entryBTestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntryReversed]

        entryATestKit.runCommand(Capture(5, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntrySuccessful]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe CreditAccount(accountA, 95, 95, 0, Set.empty)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe CreditAccount(accountB, 105, 105, 0, Set.empty)
      }
    }

    "an authorized transfer (100) is made" must {
      def entrySetup(): Unit = {
        accountSetup()
        entryATestKit.runCommand(Begin(entryA, accountA, accountB, 100, ackProbe.ref, authOnly = true))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntryPending]
      }

      "result in A = 0/100/100, B = 100/100/0 balance" in {
        entrySetup()
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe CreditAccount(accountA, 0, 100, 100, Set.empty)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe CreditAccount(accountB, 100, 100, 0, Set.empty)
      }

      "capture (100) to in A = 0/0/0, B = 200/200/0 balance" in {
        entrySetup()

        entryATestKit.runCommand(Capture(100, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntrySuccessful]

        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe CreditAccount(accountA, 0, 0, 0, Set.empty)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe CreditAccount(accountB, 200, 200, 0, Set.empty)
      }

      //This shouldn't really happen
      "stop for illegal states and wait for adjustments" in {
        entrySetup()
        // Illegal stuff
        accountATestKit.runCommand(Post("SETUP-A", "SETUP-ENTRY", 10, 0, DateUtils.now()))
        val preStateA = accountATestKit.runCommand[AccountState](Get)
        preStateA.reply shouldBe CreditAccount(accountA, 0, 90, 90, Set.empty)

        // Capture attempt stops
        entryATestKit.runCommand(Capture(100, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectNoMessage(10.seconds)

        // Adjustments
        accountATestKit.runCommand(CreditAdjust("SOME-MANUAL", "MANUAL", 10))
        accountATestKit.runCommand(DebitHold("SOME-MANUAL", "MANUAL", 10))
        val preStateA2 = accountATestKit.runCommand[AccountState](Get)
        preStateA2.reply shouldBe CreditAccount(accountA, 0, 100, 100, Set.empty)

        // Resume
        entryATestKit.runCommand(Resume(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck]
        resultProbe.expectMessageType[EntrySuccessful]

        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe CreditAccount(accountA, 0, 0, 0, Set.empty)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe CreditAccount(accountB, 200, 200, 0, Set.empty)
      }
    }
  }

}
