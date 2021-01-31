package io.openledger.transaction

import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.typesafe.config.ConfigFactory
import io.openledger.LedgerError
import io.openledger.account.Account._
import io.openledger.transaction.Transaction.{apply => _, _}
import io.openledger.transaction.states._
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID
import scala.language.postfixOps

class TransactionSpec
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

  private val stubAccountMessenger = mockFunction[String, AccountingCommand, Unit]
  private val stubResultMessenger = mockFunction[TransactionResult, Unit]
  private val txnId = UUID.randomUUID().toString
  private val accountIdToDebit = UUID.randomUUID().toString
  private val accountIdToCredit = UUID.randomUUID().toString
  private val eventSourcedTestKit = EventSourcedBehaviorTestKit[TransactionCommand, TransactionEvent, TransactionState](system, Transaction(txnId)(stubAccountMessenger, stubResultMessenger))
  private val transactionAmount = BigDecimal(1)

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    eventSourcedTestKit.clear()
  }

  "A Transaction" when {
    "Ready" must {
      "transition to Debiting after Started" in {
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, Debit(txnId, transactionAmount)) once
        }
        val beginResult = eventSourcedTestKit.runCommand(Begin(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.events shouldBe Seq(Started(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.stateOfType[Debiting].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Debiting].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Debiting].transactionId shouldBe txnId
        beginResult.stateOfType[Debiting].amount shouldBe transactionAmount
      }
    }

    "Debiting" must {
      def given(): Unit = {
        val beginResult = eventSourcedTestKit.runCommand(Begin(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.events shouldBe Seq(Started(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.stateOfType[Debiting].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Debiting].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Debiting].transactionId shouldBe txnId
        beginResult.stateOfType[Debiting].amount shouldBe transactionAmount
      }

      "transition to Crediting after DebitSucceeded" in {
        val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))

        inSequence {
          stubAccountMessenger expects(accountIdToDebit, Debit(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, transactionAmount)) once
        }

        given()

        val debitResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance))
        debitResult.events shouldBe Seq(DebitSucceeded(expectedDebitResultingBalance))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].transactionId shouldBe txnId
        debitResult.stateOfType[Crediting].amount shouldBe transactionAmount
      }

      "transition to Failed after DebitFailed" in {
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, Debit(txnId, transactionAmount)) once

          stubResultMessenger expects TransactionFailed(LedgerError.INSUFFICIENT_BALANCE) once
        }

        given()

        val debitResult = eventSourcedTestKit.runCommand(RejectAccounting(accountIdToDebit, LedgerError.INSUFFICIENT_BALANCE))
        debitResult.events shouldBe Seq(DebitFailed(LedgerError.INSUFFICIENT_BALANCE))
        debitResult.stateOfType[Failed].code shouldBe LedgerError.INSUFFICIENT_BALANCE
        debitResult.stateOfType[Failed].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Failed].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Failed].transactionId shouldBe txnId
        debitResult.stateOfType[Failed].amount shouldBe transactionAmount
      }
    }

    "Crediting" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))

      def given(): Unit = {
        val beginResult = eventSourcedTestKit.runCommand(Begin(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.events shouldBe Seq(Started(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.stateOfType[Debiting].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Debiting].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Debiting].transactionId shouldBe txnId
        beginResult.stateOfType[Debiting].amount shouldBe transactionAmount

        val debitResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance))
        debitResult.events shouldBe Seq(DebitSucceeded(expectedDebitResultingBalance))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].transactionId shouldBe txnId
        debitResult.stateOfType[Crediting].amount shouldBe transactionAmount
      }

      "transition to Posted after CreditingSucceeded" in {
        val expectedCreditResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4), BigDecimal(5))

        inSequence {
          stubAccountMessenger expects(accountIdToDebit, Debit(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, transactionAmount)) once

          stubResultMessenger expects TransactionSuccessful(expectedDebitResultingBalance, expectedCreditResultingBalance) once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToCredit, expectedCreditResultingBalance))
        creditResult.events shouldBe Seq(CreditSucceeded(expectedCreditResultingBalance))
        creditResult.stateOfType[Posted].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        creditResult.stateOfType[Posted].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posted].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posted].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posted].transactionId shouldBe txnId
        creditResult.stateOfType[Posted].amount shouldBe transactionAmount
      }

      "transition to RollingBack after CreditingFailed" in {
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, Debit(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, CreditAdjust(txnId, transactionAmount)) once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(RejectAccounting(accountIdToCredit, LedgerError.INSUFFICIENT_BALANCE))
        creditResult.events shouldBe Seq(CreditFailed(LedgerError.INSUFFICIENT_BALANCE))
        creditResult.stateOfType[RollingBackDebit].code shouldBe Some(LedgerError.INSUFFICIENT_BALANCE)
        creditResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[RollingBackDebit].transactionId shouldBe txnId
        creditResult.stateOfType[RollingBackDebit].amount shouldBe transactionAmount
      }
    }

    "RollingBackDebit due to Error" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))

      def given(): Unit = {
        val beginResult = eventSourcedTestKit.runCommand(Begin(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.events shouldBe Seq(Started(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.stateOfType[Debiting].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Debiting].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Debiting].transactionId shouldBe txnId
        beginResult.stateOfType[Debiting].amount shouldBe transactionAmount

        val debitResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance))
        debitResult.events shouldBe Seq(DebitSucceeded(expectedDebitResultingBalance))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].transactionId shouldBe txnId
        debitResult.stateOfType[Crediting].amount shouldBe transactionAmount

        val creditResult = eventSourcedTestKit.runCommand(RejectAccounting(accountIdToCredit, LedgerError.INSUFFICIENT_BALANCE))
        creditResult.events shouldBe Seq(CreditFailed(LedgerError.INSUFFICIENT_BALANCE))
        creditResult.stateOfType[RollingBackDebit].code shouldBe Some(LedgerError.INSUFFICIENT_BALANCE)
        creditResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[RollingBackDebit].transactionId shouldBe txnId
        creditResult.stateOfType[RollingBackDebit].amount shouldBe transactionAmount
      }

      "transition to Failed after CreditAdjustmentDone" in {
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, Debit(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, CreditAdjust(txnId, transactionAmount)) once

          stubResultMessenger expects TransactionFailed(LedgerError.INSUFFICIENT_BALANCE) once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance))
        creditResult.events shouldBe Seq(CreditAdjustmentDone(expectedDebitResultingBalance))
        creditResult.stateOfType[Failed].code shouldBe LedgerError.INSUFFICIENT_BALANCE
        creditResult.stateOfType[Failed].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Failed].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Failed].transactionId shouldBe txnId
        creditResult.stateOfType[Failed].amount shouldBe transactionAmount
      }
    }

    "Posted" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))
      val expectedCreditResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4), BigDecimal(5))

      def given(): Unit = {
        val beginResult = eventSourcedTestKit.runCommand(Begin(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.events shouldBe Seq(Started(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.stateOfType[Debiting].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Debiting].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Debiting].transactionId shouldBe txnId
        beginResult.stateOfType[Debiting].amount shouldBe transactionAmount

        val debitResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance))
        debitResult.events shouldBe Seq(DebitSucceeded(expectedDebitResultingBalance))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].transactionId shouldBe txnId
        debitResult.stateOfType[Crediting].amount shouldBe transactionAmount

        val creditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToCredit, expectedCreditResultingBalance))
        creditResult.events shouldBe Seq(CreditSucceeded(expectedCreditResultingBalance))
        creditResult.stateOfType[Posted].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        creditResult.stateOfType[Posted].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posted].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posted].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posted].transactionId shouldBe txnId
        creditResult.stateOfType[Posted].amount shouldBe transactionAmount
      }
      "transition to RollingBackCredit on ReversalRequested" in {
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, Debit(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, transactionAmount)) once

          stubResultMessenger expects TransactionSuccessful(expectedDebitResultingBalance, expectedCreditResultingBalance) once

          stubAccountMessenger expects(accountIdToCredit, DebitAdjust(txnId, transactionAmount)) once
        }

        given()

        val reverseResult = eventSourcedTestKit.runCommand(Reverse())
        reverseResult.events shouldBe Seq(ReversalRequested())
        reverseResult.stateOfType[RollingBackCredit].accountToDebit shouldBe accountIdToDebit
        reverseResult.stateOfType[RollingBackCredit].accountToCredit shouldBe accountIdToCredit
        reverseResult.stateOfType[RollingBackCredit].transactionId shouldBe txnId
        reverseResult.stateOfType[RollingBackCredit].amount shouldBe transactionAmount
      }
    }

    "RollingBackCredit" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))
      val expectedCreditResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4), BigDecimal(5))

      def given(): Unit = {
        val beginResult = eventSourcedTestKit.runCommand(Begin(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.events shouldBe Seq(Started(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.stateOfType[Debiting].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Debiting].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Debiting].transactionId shouldBe txnId
        beginResult.stateOfType[Debiting].amount shouldBe transactionAmount

        val debitResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance))
        debitResult.events shouldBe Seq(DebitSucceeded(expectedDebitResultingBalance))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].transactionId shouldBe txnId
        debitResult.stateOfType[Crediting].amount shouldBe transactionAmount

        val creditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToCredit, expectedCreditResultingBalance))
        creditResult.events shouldBe Seq(CreditSucceeded(expectedCreditResultingBalance))
        creditResult.stateOfType[Posted].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        creditResult.stateOfType[Posted].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posted].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posted].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posted].transactionId shouldBe txnId
        creditResult.stateOfType[Posted].amount shouldBe transactionAmount

        val reverseResult = eventSourcedTestKit.runCommand(Reverse())
        reverseResult.events shouldBe Seq(ReversalRequested())
        reverseResult.stateOfType[RollingBackCredit].accountToDebit shouldBe accountIdToDebit
        reverseResult.stateOfType[RollingBackCredit].accountToCredit shouldBe accountIdToCredit
        reverseResult.stateOfType[RollingBackCredit].transactionId shouldBe txnId
        reverseResult.stateOfType[RollingBackCredit].amount shouldBe transactionAmount
      }

      "transition to RollingBackDebit on DebitAdjustmentDone" in {
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, Debit(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, transactionAmount)) once

          stubResultMessenger expects TransactionSuccessful(expectedDebitResultingBalance, expectedCreditResultingBalance) once

          stubAccountMessenger expects(accountIdToCredit, DebitAdjust(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, CreditAdjust(txnId, transactionAmount)) once
        }

        given()

        val reverseResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToCredit, expectedCreditResultingBalance))
        reverseResult.events shouldBe Seq(DebitAdjustmentDone(expectedCreditResultingBalance))
        reverseResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        reverseResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        reverseResult.stateOfType[RollingBackDebit].transactionId shouldBe txnId
        reverseResult.stateOfType[RollingBackDebit].amount shouldBe transactionAmount
      }
    }

    "RollingBackDebit due to Reversal" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))
      val expectedCreditResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4), BigDecimal(5))

      def given(): Unit = {
        val beginResult = eventSourcedTestKit.runCommand(Begin(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.events shouldBe Seq(Started(accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.stateOfType[Debiting].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Debiting].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Debiting].transactionId shouldBe txnId
        beginResult.stateOfType[Debiting].amount shouldBe transactionAmount

        val debitResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance))
        debitResult.events shouldBe Seq(DebitSucceeded(expectedDebitResultingBalance))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].transactionId shouldBe txnId
        debitResult.stateOfType[Crediting].amount shouldBe transactionAmount

        val creditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToCredit, expectedCreditResultingBalance))
        creditResult.events shouldBe Seq(CreditSucceeded(expectedCreditResultingBalance))
        creditResult.stateOfType[Posted].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        creditResult.stateOfType[Posted].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posted].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posted].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posted].transactionId shouldBe txnId
        creditResult.stateOfType[Posted].amount shouldBe transactionAmount

        val reverseResult = eventSourcedTestKit.runCommand(Reverse())
        reverseResult.events shouldBe Seq(ReversalRequested())
        reverseResult.stateOfType[RollingBackCredit].accountToDebit shouldBe accountIdToDebit
        reverseResult.stateOfType[RollingBackCredit].accountToCredit shouldBe accountIdToCredit
        reverseResult.stateOfType[RollingBackCredit].transactionId shouldBe txnId
        reverseResult.stateOfType[RollingBackCredit].amount shouldBe transactionAmount

        val reverseCreditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToCredit, expectedCreditResultingBalance))
        reverseCreditResult.events shouldBe Seq(DebitAdjustmentDone(expectedCreditResultingBalance))
        reverseCreditResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        reverseCreditResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        reverseCreditResult.stateOfType[RollingBackDebit].transactionId shouldBe txnId
        reverseCreditResult.stateOfType[RollingBackDebit].amount shouldBe transactionAmount
      }

      "transition to Reversed after CreditAdjustmentDone" in {
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, Debit(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, transactionAmount)) once

          stubResultMessenger expects TransactionSuccessful(expectedDebitResultingBalance, expectedCreditResultingBalance) once

          stubAccountMessenger expects(accountIdToCredit, DebitAdjust(txnId, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, CreditAdjust(txnId, transactionAmount)) once

          stubResultMessenger expects TransactionReversed() once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance))
        creditResult.events shouldBe Seq(CreditAdjustmentDone(expectedDebitResultingBalance))
        creditResult.stateOfType[Reversed].transactionId shouldBe txnId
      }
    }
  }

}
