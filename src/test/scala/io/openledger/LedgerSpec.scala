package io.openledger

import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.typesafe.config.ConfigFactory
import io.openledger.account.Account._
import io.openledger.account.states.{AccountState, CreditAccount, DebitAccount}
import io.openledger.account.{Account, AccountMode}
import io.openledger.transaction.Transaction
import io.openledger.transaction.Transaction.{apply => _, _}
import io.openledger.transaction.states.TransactionState
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class LedgerSpec
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

  private val logger: Logger = LoggerFactory.getLogger(classOf[LedgerSpec])

  private val txnA = "TXN-A"
  private val entryA = "ENTRY-A"
  private val txnB = "TXN-B"
  private val entryB = "ENTRY-B"
  private val accountA = "ACCOUNT-A"
  private val accountB = "ACCOUNT-B"
  private val accountATestKit = EventSourcedBehaviorTestKit[AccountCommand, AccountEvent, AccountState](system, Account(accountA)(stubTransactionMessenger, () => DateUtils.now()))
  private val accountBTestKit = EventSourcedBehaviorTestKit[AccountCommand, AccountEvent, AccountState](system, Account(accountB)(stubTransactionMessenger, () => DateUtils.now()))
  private val transactionATestKit = EventSourcedBehaviorTestKit[TransactionCommand, TransactionEvent, TransactionState](system, Transaction(txnA)(stubAccountMessenger, stubResultMessenger))
  private val transactionBTestKit = EventSourcedBehaviorTestKit[TransactionCommand, TransactionEvent, TransactionState](system, Transaction(txnB)(stubAccountMessenger, stubResultMessenger))
  private val resultProbe = testKit.createTestProbe[TransactionResult]


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

  def stubResultMessenger(message: TransactionResult): Unit = {
    logger.info(s"Sending Result to Probe $message")
    resultProbe ! message
  }

  def stubTransactionMessenger(transactionId: String, message: AccountingStatus): Unit = {
    if (transactionId == txnA) {
      logger.info(s"Sending to Transaction A $message")
      val sendThis = message match {
        case AccountingSuccessful(accountId, availableBalance, currentBalance, _, timestamp) =>
          AcceptAccounting(accountId, ResultingBalance(availableBalance, currentBalance), timestamp)
        case AccountingFailed(accountId, code) =>
          RejectAccounting(accountId, code)
      }
      akka.pattern.after(10.millisecond) {
        Future {
          logger.info(s"Send to Transaction A $sendThis")
          transactionATestKit.runCommand(sendThis)
        }
      }

    } else if (transactionId == txnB) {
      logger.info(s"Sending to Transaction B $message")
      val sendThis = message match {
        case AccountingSuccessful(accountId, availableBalance, currentBalance, _, timestamp) =>
          AcceptAccounting(accountId, ResultingBalance(availableBalance, currentBalance), timestamp)
        case AccountingFailed(accountId, code) =>
          RejectAccounting(accountId, code)
      }
      akka.pattern.after(10.millisecond) {
        Future {
          logger.info(s"Send to Transaction B $sendThis")
          transactionBTestKit.runCommand(sendThis)
        }
      }

    } else {
      logger.info(s"Transaction sending Ignored")
    }
  }

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    transactionATestKit.clear()
    transactionBTestKit.clear()
    accountATestKit.clear()
    accountBTestKit.clear()
  }

  "Two Debit Accounts with A = 100/100/0, B = 100/100/0 balance" when {
    def accountSetup(): Unit = {
      accountATestKit.runCommand(Open(AccountMode.DEBIT))
      accountBTestKit.runCommand(Open(AccountMode.DEBIT))
      accountATestKit.runCommand(Debit("SETUP-A", "SETUP-ENTRY", 100))
      accountBTestKit.runCommand(Debit("SETUP-B", "SETUP-ENTRY", 100))
    }

    "a simple transfer (10) is made" must {
      def transactionSetup(): Unit = {
        accountSetup()
        transactionATestKit.runCommand(Begin(entryA, accountA, accountB, 10))
        resultProbe.expectMessageType[TransactionSuccessful]
      }

      "result in A = 110/110/0, B = 90/90/0" in {
        transactionSetup()
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe DebitAccount(accountA, 110, 110, 0)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe DebitAccount(accountB, 90, 90, 0)
      }

      "be reversed back to A = 100/100/0, B = 100/100/0 balance" in {
        transactionSetup()
        transactionATestKit.runCommand(Reverse())
        resultProbe.expectMessageType[TransactionReversed]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe DebitAccount(accountA, 100, 100, 0)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe DebitAccount(accountB, 100, 100, 0)
      }
    }

    "an authorized transfer (10) is made" must {
      def transactionSetup(): Unit = {
        accountSetup()
        transactionATestKit.runCommand(Begin(entryA, accountA, accountB, 10, authOnly = true))
        resultProbe.expectMessageType[TransactionPending]
      }

      "result in A = 110/100/10, B = 100/100/0" in {
        transactionSetup()
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe DebitAccount(accountA, 110, 100, 10)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe DebitAccount(accountB, 100, 100, 0)
      }

      "be reversed back to A = 100/100/0, B = 100/100/0 balance" in {
        transactionSetup()
        transactionATestKit.runCommand(Reverse())
        resultProbe.expectMessageType[TransactionReversed]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe DebitAccount(accountA, 100, 100, 0)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe DebitAccount(accountB, 100, 100, 0)
      }

      "be partially captured (5) to A = 105/105/0, B = 95/95/0 balance" in {
        transactionSetup()
        transactionATestKit.runCommand(Capture(5))
        resultProbe.expectMessageType[TransactionSuccessful]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe DebitAccount(accountA, 105, 105, 0)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe DebitAccount(accountB, 95, 95, 0)
      }

      "not react to over-capture and then partially captured (5) to A = 105/105/0, B = 95/95/0 balance" in {
        transactionSetup()

        transactionATestKit.runCommand(Capture(11))
        resultProbe.expectMessageType[CaptureRejected]

        transactionATestKit.runCommand(Capture(5))
        resultProbe.expectMessageType[TransactionSuccessful]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe DebitAccount(accountA, 105, 105, 0)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe DebitAccount(accountB, 95, 95, 0)
      }

      "allow another authorize (15) followed by a partial capture (5) to A = 120/105/15, B = 95/95/0 balance" in {
        transactionSetup()

        transactionBTestKit.runCommand(Begin(entryB, accountA, accountB, 15, authOnly = true))
        resultProbe.expectMessageType[TransactionPending]

        transactionATestKit.runCommand(Capture(5))
        resultProbe.expectMessageType[TransactionSuccessful]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe DebitAccount(accountA, 120, 105, 15)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe DebitAccount(accountB, 95, 95, 0)
      }

      "allow another authorize (15), reversed, followed by a partial capture (5) to A = 105/105/0, B = 95/95/0 balance" in {
        transactionSetup()

        transactionBTestKit.runCommand(Begin(entryB, accountA, accountB, 15, authOnly = true))
        resultProbe.expectMessageType[TransactionPending]

        transactionBTestKit.runCommand(Reverse())
        resultProbe.expectMessageType[TransactionReversed]

        transactionATestKit.runCommand(Capture(5))
        resultProbe.expectMessageType[TransactionSuccessful]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe DebitAccount(accountA, 105, 105, 0)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe DebitAccount(accountB, 95, 95, 0)
      }

      "allow another transfer (15) followed by a partial capture (5) to A = 120/120/0, B = 80/80/0 balance" in {
        transactionSetup()

        transactionBTestKit.runCommand(Begin(entryB, accountA, accountB, 15))
        resultProbe.expectMessageType[TransactionSuccessful]

        transactionATestKit.runCommand(Capture(5))
        resultProbe.expectMessageType[TransactionSuccessful]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe DebitAccount(accountA, 120, 120, 0)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe DebitAccount(accountB, 80, 80, 0)
      }

      "allow another transfer (15), reversed, followed by a partial capture (5) to A = 105/105/0, B = 95/95/0 balance" in {
        transactionSetup()

        transactionBTestKit.runCommand(Begin(entryB, accountA, accountB, 15))
        resultProbe.expectMessageType[TransactionSuccessful]

        transactionBTestKit.runCommand(Reverse())
        resultProbe.expectMessageType[TransactionReversed]

        transactionATestKit.runCommand(Capture(5))
        resultProbe.expectMessageType[TransactionSuccessful]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe DebitAccount(accountA, 105, 105, 0)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe DebitAccount(accountB, 95, 95, 0)
      }
    }
  }

  "Two Debit Accounts with A = 0/0/0, B = 0/0/0 balance" when {
    def accountSetup(): Unit = {
      accountATestKit.runCommand(Open(AccountMode.DEBIT))
      accountBTestKit.runCommand(Open(AccountMode.DEBIT))
    }

    "a simple transfer (10) is made" must {
      def transactionSetup(): Unit = {
        accountSetup()
        transactionATestKit.runCommand(Begin(entryA, accountA, accountB, 10))
        resultProbe.expectMessageType[TransactionFailed]
      }

      "result in INSUFFICIENT_BALANCE for B, and reverse to A = 0/0/0, B = 0/0/0" in {
        transactionSetup()
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe DebitAccount(accountA, 0, 0, 0)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe DebitAccount(accountB, 0, 0, 0)
      }
    }

    "an authorized transfer (10) is made" must {
      def transactionSetup(): Unit = {
        accountSetup()
        transactionATestKit.runCommand(Begin(entryA, accountA, accountB, 10, authOnly = true))
        resultProbe.expectMessageType[TransactionPending]
      }

      //TODO: Not sure if this is a proper behavior or should remain Pending
      "capture result in INSUFFICIENT_BALANCE for B, and reverse to A = 0/0/0, B = 0/0/0" in {
        transactionSetup()

        transactionATestKit.runCommand(Capture(1))
        resultProbe.expectMessageType[TransactionFailed]

        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe DebitAccount(accountA, 0, 0, 0)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe DebitAccount(accountB, 0, 0, 0)
      }


    }
  }

  "Two Credit Accounts with A = 100/100/0, B = 100/100/0 balance" when {
    def accountSetup(): Unit = {
      accountATestKit.runCommand(Open(AccountMode.CREDIT))
      accountBTestKit.runCommand(Open(AccountMode.CREDIT))
      accountATestKit.runCommand(Credit("SETUP-A", "SETUP-ENTRY", 100))
      accountBTestKit.runCommand(Credit("SETUP-B", "SETUP-ENTRY", 100))
    }

    "a simple transfer (10) is made" must {
      def transactionSetup(): Unit = {
        accountSetup()
        transactionATestKit.runCommand(Begin(entryA, accountA, accountB, 10))
        resultProbe.expectMessageType[TransactionSuccessful]
      }

      "result in A = 90/90/0, B = 110/110/0" in {
        transactionSetup()
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe CreditAccount(accountA, 90, 90, 0)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe CreditAccount(accountB, 110, 110, 0)
      }

      "be reversed back to A = 100/100/0, B = 100/100/0 balance" in {
        transactionSetup()
        transactionATestKit.runCommand(Reverse())
        resultProbe.expectMessageType[TransactionReversed]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe CreditAccount(accountA, 100, 100, 0)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe CreditAccount(accountB, 100, 100, 0)
      }
    }

    "an authorized transfer (10) is made" must {
      def transactionSetup(): Unit = {
        accountSetup()
        transactionATestKit.runCommand(Begin(entryA, accountA, accountB, 10, authOnly = true))
        resultProbe.expectMessageType[TransactionPending]
      }

      "result in A = 90/100/10, B = 100/100/0" in {
        transactionSetup()
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe CreditAccount(accountA, 90, 100, 10)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe CreditAccount(accountB, 100, 100, 0)
      }

      "be reversed back to A = 100/100/0, B = 100/100/0 balance" in {
        transactionSetup()
        transactionATestKit.runCommand(Reverse())
        resultProbe.expectMessageType[TransactionReversed]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe CreditAccount(accountA, 100, 100, 0)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe CreditAccount(accountB, 100, 100, 0)
      }

      "be partially captured (5) to A = 95/95/0, B = 105/105/0 balance" in {
        transactionSetup()
        transactionATestKit.runCommand(Capture(5))
        resultProbe.expectMessageType[TransactionSuccessful]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe CreditAccount(accountA, 95, 95, 0)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe CreditAccount(accountB, 105, 105, 0)
      }

      "not react to over-capture and then partially captured (5) to A = 95/95/0, B = 105/105/0 balance" in {
        transactionSetup()

        transactionATestKit.runCommand(Capture(11))
        resultProbe.expectMessageType[CaptureRejected]

        transactionATestKit.runCommand(Capture(5))
        resultProbe.expectMessageType[TransactionSuccessful]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe CreditAccount(accountA, 95, 95, 0)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe CreditAccount(accountB, 105, 105, 0)
      }

      "allow another authorize (15) followed by a partial capture (5) to A = 80/95/15, B = 105/105/0 balance" in {
        transactionSetup()

        transactionBTestKit.runCommand(Begin(entryB, accountA, accountB, 15, authOnly = true))
        resultProbe.expectMessageType[TransactionPending]

        transactionATestKit.runCommand(Capture(5))
        resultProbe.expectMessageType[TransactionSuccessful]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe CreditAccount(accountA, 80, 95, 15)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe CreditAccount(accountB, 105, 105, 0)
      }

      "allow another authorize (15), reversed, followed by a partial capture (5) to A = 95/95/0, B = 105/105/0 balance" in {
        transactionSetup()

        transactionBTestKit.runCommand(Begin(entryB, accountA, accountB, 15, authOnly = true))
        resultProbe.expectMessageType[TransactionPending]

        transactionBTestKit.runCommand(Reverse())
        resultProbe.expectMessageType[TransactionReversed]

        transactionATestKit.runCommand(Capture(5))
        resultProbe.expectMessageType[TransactionSuccessful]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe CreditAccount(accountA, 95, 95, 0)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe CreditAccount(accountB, 105, 105, 0)
      }

      "allow another transfer (15) followed by a partial capture (5) to A = 80/80/0, B = 120/120/0 balance" in {
        transactionSetup()

        transactionBTestKit.runCommand(Begin(entryB, accountA, accountB, 15))
        resultProbe.expectMessageType[TransactionSuccessful]

        transactionATestKit.runCommand(Capture(5))
        resultProbe.expectMessageType[TransactionSuccessful]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe CreditAccount(accountA, 80, 80, 0)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe CreditAccount(accountB, 120, 120, 0)
      }

      "allow another transfer (15), reversed, followed by a partial capture (5) to A = 95/95/0, B = 105/105/0 balance" in {
        transactionSetup()

        transactionBTestKit.runCommand(Begin(entryB, accountA, accountB, 15))
        resultProbe.expectMessageType[TransactionSuccessful]

        transactionBTestKit.runCommand(Reverse())
        resultProbe.expectMessageType[TransactionReversed]

        transactionATestKit.runCommand(Capture(5))
        resultProbe.expectMessageType[TransactionSuccessful]
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe CreditAccount(accountA, 95, 95, 0)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe CreditAccount(accountB, 105, 105, 0)
      }
    }

    "an authorized transfer (100) is made" must {
      def transactionSetup(): Unit = {
        accountSetup()
        transactionATestKit.runCommand(Begin(entryA, accountA, accountB, 100, authOnly = true))
        resultProbe.expectMessageType[TransactionPending]
      }

      "result in A = 0/100/100, B = 100/100/0 balance" in {
        transactionSetup()
        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe CreditAccount(accountA, 0, 100, 100)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe CreditAccount(accountB, 100, 100, 0)
      }

      "capture (100) to in A = 0/0/0, B = 200/200/0 balance" in {
        transactionSetup()

        transactionATestKit.runCommand(Capture(100))
        resultProbe.expectMessageType[TransactionSuccessful]

        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe CreditAccount(accountA, 0, 0, 0)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe CreditAccount(accountB, 200, 200, 0)
      }

      //This shouldn't really happen
      "stop for illegal states and wait for adjustments" in {
        transactionSetup()
        // Illegal stuff
        accountATestKit.runCommand(Post("SETUP-A", "SETUP-ENTRY", 10, 0, DateUtils.now()))
        val preStateA = accountATestKit.runCommand[AccountState](Get)
        preStateA.reply shouldBe CreditAccount(accountA, 0, 90, 90)

        // Capture attempt stops
        transactionATestKit.runCommand(Capture(100))
        resultProbe.expectNoMessage(10.seconds)

        // Adjustments
        accountATestKit.runCommand(CreditAdjust("SOME-MANUAL", "MANUAL", 10))
        accountATestKit.runCommand(DebitHold("SOME-MANUAL", "MANUAL", 10))
        val preStateA2 = accountATestKit.runCommand[AccountState](Get)
        preStateA2.reply shouldBe CreditAccount(accountA, 0, 100, 100)

        // Resume
        transactionATestKit.runCommand(Resume())
        resultProbe.expectMessageType[TransactionSuccessful]

        val stateA = accountATestKit.runCommand[AccountState](Get)
        stateA.reply shouldBe CreditAccount(accountA, 0, 0, 0)
        val stateB = accountBTestKit.runCommand[AccountState](Get)
        stateB.reply shouldBe CreditAccount(accountB, 200, 200, 0)
      }
    }
  }


}
