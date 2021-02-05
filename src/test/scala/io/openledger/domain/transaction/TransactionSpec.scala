package io.openledger.domain.transaction

import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.typesafe.config.ConfigFactory
import io.openledger.domain.account.Account._
import io.openledger.domain.transaction.Transaction.{apply => _, _}
import io.openledger.domain.transaction.states._
import io.openledger.{DateUtils, LedgerError, ResultingBalance}
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Duration
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
  private val entryCode = "ENTRY"
  private val setupEntryCode = "ENTRY"
  private val accountIdToDebit = UUID.randomUUID().toString
  private val accountIdToCredit = UUID.randomUUID().toString
  private val eventSourcedTestKit = EventSourcedBehaviorTestKit[TransactionCommand, TransactionEvent, TransactionState](system, Transaction(txnId)(stubAccountMessenger, stubResultMessenger))
  private val transactionAmount = BigDecimal(100)
  private val theTime = DateUtils.now()

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    eventSourcedTestKit.clear()
  }

  "A Transaction" when {
    "Ready" must {
      "transition to Authorizing after Started" in {
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, setupEntryCode, transactionAmount)) once
        }
        val beginResult = eventSourcedTestKit.runCommand(Begin(entryCode, accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.events shouldBe Seq(Started(entryCode, accountIdToDebit, accountIdToCredit, transactionAmount, authOnly = false))
        beginResult.stateOfType[Authorizing].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Authorizing].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Authorizing].entryCode shouldBe entryCode
        beginResult.stateOfType[Authorizing].transactionId shouldBe txnId
        beginResult.stateOfType[Authorizing].amountAuthorized shouldBe transactionAmount
      }
    }

    "Authorizing" must {
      def given(): Unit = {
        val beginResult = eventSourcedTestKit.runCommand(Begin(entryCode, accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.events shouldBe Seq(Started(entryCode, accountIdToDebit, accountIdToCredit, transactionAmount, authOnly = false))
        beginResult.stateOfType[Authorizing].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Authorizing].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Authorizing].entryCode shouldBe entryCode
        beginResult.stateOfType[Authorizing].transactionId shouldBe txnId
        beginResult.stateOfType[Authorizing].amountAuthorized shouldBe transactionAmount
      }

      "transition to Crediting after DebitHoldSucceeded" in {
        val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))

        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, setupEntryCode, transactionAmount)) once
        }

        given()

        val debitResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance, theTime))
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, theTime))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].entryCode shouldBe entryCode
        debitResult.stateOfType[Crediting].transactionId shouldBe txnId
        debitResult.stateOfType[Crediting].amountAuthorized shouldBe transactionAmount
        debitResult.stateOfType[Crediting].captureAmount shouldBe transactionAmount
        debitResult.stateOfType[Crediting].debitHoldTimestamp shouldBe theTime
      }

      "transition to Failed after DebitHoldFailed" in {
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, setupEntryCode, transactionAmount)) once

          stubResultMessenger expects TransactionFailed(LedgerError.INSUFFICIENT_BALANCE) once
        }

        given()

        val debitResult = eventSourcedTestKit.runCommand(RejectAccounting(accountIdToDebit, LedgerError.INSUFFICIENT_BALANCE))
        debitResult.events shouldBe Seq(DebitHoldFailed(LedgerError.INSUFFICIENT_BALANCE))
        debitResult.stateOfType[Failed].code shouldBe LedgerError.INSUFFICIENT_BALANCE
        debitResult.stateOfType[Failed].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Failed].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Failed].entryCode shouldBe entryCode
        debitResult.stateOfType[Failed].transactionId shouldBe txnId
        debitResult.stateOfType[Failed].amount shouldBe transactionAmount
      }
    }

    "Crediting" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))
      val holdTime = DateUtils.now().minus(Duration.ofDays(1))

      def given(): Unit = {
        val beginResult = eventSourcedTestKit.runCommand(Begin(entryCode, accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.events shouldBe Seq(Started(entryCode, accountIdToDebit, accountIdToCredit, transactionAmount, authOnly = false))
        beginResult.stateOfType[Authorizing].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Authorizing].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Authorizing].entryCode shouldBe entryCode
        beginResult.stateOfType[Authorizing].transactionId shouldBe txnId
        beginResult.stateOfType[Authorizing].amountAuthorized shouldBe transactionAmount

        val debitResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance, holdTime))
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, holdTime))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].entryCode shouldBe entryCode
        debitResult.stateOfType[Crediting].transactionId shouldBe txnId
        debitResult.stateOfType[Crediting].amountAuthorized shouldBe transactionAmount
        debitResult.stateOfType[Crediting].captureAmount shouldBe transactionAmount
        debitResult.stateOfType[Crediting].debitHoldTimestamp shouldBe holdTime
      }

      "transition to Posting after CreditingSucceeded" in {
        val expectedCreditResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4), BigDecimal(5))
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, Post(txnId, setupEntryCode, transactionAmount, BigDecimal(0), holdTime)) once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToCredit, expectedCreditResultingBalance, theTime))
        creditResult.events shouldBe Seq(CreditSucceeded(expectedCreditResultingBalance))
        creditResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        creditResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posting].entryCode shouldBe entryCode
        creditResult.stateOfType[Posting].transactionId shouldBe txnId
        creditResult.stateOfType[Posting].amountAuthorized shouldBe transactionAmount
        creditResult.stateOfType[Posting].captureAmount shouldBe transactionAmount
        creditResult.stateOfType[Posting].debitHoldTimestamp shouldBe holdTime

      }

      "transition to RollingBackDebit after CreditingFailed" in {
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, Release(txnId, setupEntryCode, transactionAmount)) once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(RejectAccounting(accountIdToCredit, LedgerError.INSUFFICIENT_BALANCE))
        creditResult.events shouldBe Seq(CreditFailed(LedgerError.INSUFFICIENT_BALANCE))
        creditResult.stateOfType[RollingBackDebit].code shouldBe Some(LedgerError.INSUFFICIENT_BALANCE)
        creditResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[RollingBackDebit].entryCode shouldBe entryCode
        creditResult.stateOfType[RollingBackDebit].transactionId shouldBe txnId
        creditResult.stateOfType[RollingBackDebit].authorizedAmount shouldBe transactionAmount
        creditResult.stateOfType[RollingBackDebit].amountCaptured shouldBe None
      }
    }

    "RollingBackDebit due to Error" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))

      def given(): Unit = {
        val beginResult = eventSourcedTestKit.runCommand(Begin(entryCode, accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.events shouldBe Seq(Started(entryCode, accountIdToDebit, accountIdToCredit, transactionAmount, authOnly = false))
        beginResult.stateOfType[Authorizing].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Authorizing].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Authorizing].entryCode shouldBe entryCode
        beginResult.stateOfType[Authorizing].transactionId shouldBe txnId
        beginResult.stateOfType[Authorizing].amountAuthorized shouldBe transactionAmount

        val debitResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance, theTime))
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, theTime))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].entryCode shouldBe entryCode
        debitResult.stateOfType[Crediting].transactionId shouldBe txnId
        debitResult.stateOfType[Crediting].amountAuthorized shouldBe transactionAmount
        debitResult.stateOfType[Crediting].captureAmount shouldBe transactionAmount
        debitResult.stateOfType[Crediting].debitHoldTimestamp shouldBe theTime

        val creditResult = eventSourcedTestKit.runCommand(RejectAccounting(accountIdToCredit, LedgerError.INSUFFICIENT_BALANCE))
        creditResult.events shouldBe Seq(CreditFailed(LedgerError.INSUFFICIENT_BALANCE))
        creditResult.stateOfType[RollingBackDebit].code shouldBe Some(LedgerError.INSUFFICIENT_BALANCE)
        creditResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[RollingBackDebit].entryCode shouldBe entryCode
        creditResult.stateOfType[RollingBackDebit].transactionId shouldBe txnId
        creditResult.stateOfType[RollingBackDebit].authorizedAmount shouldBe transactionAmount
        creditResult.stateOfType[RollingBackDebit].amountCaptured shouldBe None
      }

      "transition to Failed after CreditAdjustmentDone (Release)" in {
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, Release(txnId, setupEntryCode, transactionAmount)) once

          stubResultMessenger expects TransactionFailed(LedgerError.INSUFFICIENT_BALANCE) once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance, theTime))
        creditResult.events shouldBe Seq(CreditAdjustmentDone(expectedDebitResultingBalance))
        creditResult.stateOfType[Failed].code shouldBe LedgerError.INSUFFICIENT_BALANCE
        creditResult.stateOfType[Failed].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Failed].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Failed].entryCode shouldBe entryCode
        creditResult.stateOfType[Failed].transactionId shouldBe txnId
        creditResult.stateOfType[Failed].amount shouldBe transactionAmount
      }

      "remain in RollingBackDebit on CreditAdjustmentFailed then resume to Failed" in {
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, Release(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, Release(txnId, setupEntryCode, transactionAmount)) once

          stubResultMessenger expects TransactionFailed(LedgerError.INSUFFICIENT_BALANCE) once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(RejectAccounting(accountIdToDebit, LedgerError.INSUFFICIENT_BALANCE))
        creditResult.events shouldBe Seq(CreditAdjustmentFailed())
        creditResult.stateOfType[RollingBackDebit].code shouldBe Some(LedgerError.INSUFFICIENT_BALANCE)
        creditResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[RollingBackDebit].entryCode shouldBe entryCode
        creditResult.stateOfType[RollingBackDebit].transactionId shouldBe txnId
        creditResult.stateOfType[RollingBackDebit].authorizedAmount shouldBe transactionAmount
        creditResult.stateOfType[RollingBackDebit].amountCaptured shouldBe None

        val resumeResult = eventSourcedTestKit.runCommand(Resume())
        resumeResult.hasNoEvents shouldBe true
        resumeResult.stateOfType[RollingBackDebit].code shouldBe Some(LedgerError.INSUFFICIENT_BALANCE)
        resumeResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        resumeResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        resumeResult.stateOfType[RollingBackDebit].entryCode shouldBe entryCode
        resumeResult.stateOfType[RollingBackDebit].transactionId shouldBe txnId
        resumeResult.stateOfType[RollingBackDebit].authorizedAmount shouldBe transactionAmount
        resumeResult.stateOfType[RollingBackDebit].amountCaptured shouldBe None

        val rollbackResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance, theTime))
        rollbackResult.events shouldBe Seq(CreditAdjustmentDone(expectedDebitResultingBalance))
        rollbackResult.stateOfType[Failed].code shouldBe LedgerError.INSUFFICIENT_BALANCE
        rollbackResult.stateOfType[Failed].accountToDebit shouldBe accountIdToDebit
        rollbackResult.stateOfType[Failed].accountToCredit shouldBe accountIdToCredit
        rollbackResult.stateOfType[Failed].entryCode shouldBe entryCode
        rollbackResult.stateOfType[Failed].transactionId shouldBe txnId
        rollbackResult.stateOfType[Failed].amount shouldBe transactionAmount
      }
    }
    "Posting" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))
      val expectedCreditResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4), BigDecimal(5))
      val holdTime = DateUtils.now().minus(Duration.ofDays(1))

      def given(): Unit = {
        val beginResult = eventSourcedTestKit.runCommand(Begin(entryCode, accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.events shouldBe Seq(Started(entryCode, accountIdToDebit, accountIdToCredit, transactionAmount, authOnly = false))
        beginResult.stateOfType[Authorizing].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Authorizing].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Authorizing].entryCode shouldBe entryCode
        beginResult.stateOfType[Authorizing].transactionId shouldBe txnId
        beginResult.stateOfType[Authorizing].amountAuthorized shouldBe transactionAmount

        val debitResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance, holdTime))
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, holdTime))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].entryCode shouldBe entryCode
        debitResult.stateOfType[Crediting].transactionId shouldBe txnId
        debitResult.stateOfType[Crediting].amountAuthorized shouldBe transactionAmount
        debitResult.stateOfType[Crediting].captureAmount shouldBe transactionAmount
        debitResult.stateOfType[Crediting].debitHoldTimestamp shouldBe holdTime

        val creditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToCredit, expectedCreditResultingBalance, theTime))
        creditResult.events shouldBe Seq(CreditSucceeded(expectedCreditResultingBalance))
        creditResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        creditResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posting].entryCode shouldBe entryCode
        creditResult.stateOfType[Posting].transactionId shouldBe txnId
        creditResult.stateOfType[Posting].amountAuthorized shouldBe transactionAmount
        creditResult.stateOfType[Posting].captureAmount shouldBe transactionAmount
        creditResult.stateOfType[Posting].debitHoldTimestamp shouldBe holdTime
      }

      "transition to Posted after DebitPostSucceeded" in {
        val debitPostedAccountResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, Post(txnId, setupEntryCode, transactionAmount, BigDecimal(0), holdTime)) once

          stubResultMessenger expects TransactionSuccessful(debitPostedAccountResultingBalance, expectedCreditResultingBalance) once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, debitPostedAccountResultingBalance, theTime))
        creditResult.events shouldBe Seq(DebitPostSucceeded(debitPostedAccountResultingBalance))
        creditResult.stateOfType[Posted].debitedAccountResultingBalance shouldBe debitPostedAccountResultingBalance
        creditResult.stateOfType[Posted].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posted].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posted].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posted].entryCode shouldBe entryCode
        creditResult.stateOfType[Posted].transactionId shouldBe txnId
        creditResult.stateOfType[Posted].amountCaptured shouldBe transactionAmount
      }

      "remain in Posting after DebitPostFailed and resume to Posted" in {
        val debitPostedAccountResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, Post(txnId, setupEntryCode, transactionAmount, BigDecimal(0), holdTime)) once //"twice" doesn't work, strangely

          stubAccountMessenger expects(accountIdToDebit, Post(txnId, setupEntryCode, transactionAmount, BigDecimal(0), holdTime)) once

          stubResultMessenger expects TransactionSuccessful(debitPostedAccountResultingBalance, expectedCreditResultingBalance) once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(RejectAccounting(accountIdToDebit, LedgerError.INSUFFICIENT_BALANCE))
        creditResult.events shouldBe Seq(DebitPostFailed())
        creditResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        creditResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posting].entryCode shouldBe entryCode
        creditResult.stateOfType[Posting].transactionId shouldBe txnId
        creditResult.stateOfType[Posting].amountAuthorized shouldBe transactionAmount
        creditResult.stateOfType[Posting].captureAmount shouldBe transactionAmount
        creditResult.stateOfType[Posting].debitHoldTimestamp shouldBe holdTime

        val resumeResult = eventSourcedTestKit.runCommand(Resume())
        resumeResult.hasNoEvents shouldBe true
        resumeResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        resumeResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        resumeResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        resumeResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        resumeResult.stateOfType[Posting].entryCode shouldBe entryCode
        resumeResult.stateOfType[Posting].transactionId shouldBe txnId
        resumeResult.stateOfType[Posting].amountAuthorized shouldBe transactionAmount
        resumeResult.stateOfType[Posting].captureAmount shouldBe transactionAmount
        resumeResult.stateOfType[Posting].debitHoldTimestamp shouldBe holdTime

        val postingResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, debitPostedAccountResultingBalance, theTime))
        postingResult.events shouldBe Seq(DebitPostSucceeded(debitPostedAccountResultingBalance))
        postingResult.stateOfType[Posted].debitedAccountResultingBalance shouldBe debitPostedAccountResultingBalance
        postingResult.stateOfType[Posted].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        postingResult.stateOfType[Posted].accountToDebit shouldBe accountIdToDebit
        postingResult.stateOfType[Posted].accountToCredit shouldBe accountIdToCredit
        postingResult.stateOfType[Posted].entryCode shouldBe entryCode
        postingResult.stateOfType[Posted].transactionId shouldBe txnId
        postingResult.stateOfType[Posted].amountCaptured shouldBe transactionAmount
      }
    }
    "Posted" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))
      val expectedCreditResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4), BigDecimal(5))
      val expectedDebitPostedBalance: ResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

      def given(): Unit = {
        val beginResult = eventSourcedTestKit.runCommand(Begin(entryCode, accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.events shouldBe Seq(Started(entryCode, accountIdToDebit, accountIdToCredit, transactionAmount, authOnly = false))
        beginResult.stateOfType[Authorizing].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Authorizing].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Authorizing].entryCode shouldBe entryCode
        beginResult.stateOfType[Authorizing].transactionId shouldBe txnId
        beginResult.stateOfType[Authorizing].amountAuthorized shouldBe transactionAmount

        val debitResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance, theTime))
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, theTime))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].entryCode shouldBe entryCode
        debitResult.stateOfType[Crediting].transactionId shouldBe txnId
        debitResult.stateOfType[Crediting].amountAuthorized shouldBe transactionAmount
        debitResult.stateOfType[Crediting].captureAmount shouldBe transactionAmount
        debitResult.stateOfType[Crediting].debitHoldTimestamp shouldBe theTime

        val creditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToCredit, expectedCreditResultingBalance, theTime))
        creditResult.events shouldBe Seq(CreditSucceeded(expectedCreditResultingBalance))
        creditResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        creditResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posting].entryCode shouldBe entryCode
        creditResult.stateOfType[Posting].transactionId shouldBe txnId
        creditResult.stateOfType[Posting].amountAuthorized shouldBe transactionAmount
        creditResult.stateOfType[Posting].captureAmount shouldBe transactionAmount
        creditResult.stateOfType[Posting].debitHoldTimestamp shouldBe theTime

        val postingResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitPostedBalance, theTime))
        postingResult.events shouldBe Seq(DebitPostSucceeded(expectedDebitPostedBalance))
        postingResult.stateOfType[Posted].debitedAccountResultingBalance shouldBe expectedDebitPostedBalance
        postingResult.stateOfType[Posted].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        postingResult.stateOfType[Posted].accountToDebit shouldBe accountIdToDebit
        postingResult.stateOfType[Posted].accountToCredit shouldBe accountIdToCredit
        postingResult.stateOfType[Posted].entryCode shouldBe entryCode
        postingResult.stateOfType[Posted].transactionId shouldBe txnId
        postingResult.stateOfType[Posted].amountCaptured shouldBe transactionAmount
      }

      "transition to RollingBackCredit on ReversalRequested" in {
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, Post(txnId, setupEntryCode, transactionAmount, BigDecimal(0), theTime)) once

          stubResultMessenger expects TransactionSuccessful(expectedDebitPostedBalance, expectedCreditResultingBalance) once

          stubAccountMessenger expects(accountIdToCredit, DebitAdjust(txnId, setupEntryCode, transactionAmount)) once
        }

        given()

        val reverseResult = eventSourcedTestKit.runCommand(Reverse())
        reverseResult.events shouldBe Seq(ReversalRequested())
        reverseResult.stateOfType[RollingBackCredit].accountToDebit shouldBe accountIdToDebit
        reverseResult.stateOfType[RollingBackCredit].accountToCredit shouldBe accountIdToCredit
        reverseResult.stateOfType[RollingBackCredit].entryCode shouldBe entryCode
        reverseResult.stateOfType[RollingBackCredit].transactionId shouldBe txnId
        reverseResult.stateOfType[RollingBackCredit].creditedAmount shouldBe transactionAmount
        reverseResult.stateOfType[RollingBackCredit].amountCaptured shouldBe Some(transactionAmount)
        reverseResult.stateOfType[RollingBackCredit].code shouldBe None
      }
    }

    "RollingBackCredit" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))
      val expectedCreditResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4), BigDecimal(5))
      val expectedDebitPostedBalance: ResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

      def given(): Unit = {
        val beginResult = eventSourcedTestKit.runCommand(Begin(entryCode, accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.events shouldBe Seq(Started(entryCode, accountIdToDebit, accountIdToCredit, transactionAmount, authOnly = false))
        beginResult.stateOfType[Authorizing].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Authorizing].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Authorizing].entryCode shouldBe entryCode
        beginResult.stateOfType[Authorizing].transactionId shouldBe txnId
        beginResult.stateOfType[Authorizing].amountAuthorized shouldBe transactionAmount

        val debitResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance, theTime))
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, theTime))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].entryCode shouldBe entryCode
        debitResult.stateOfType[Crediting].transactionId shouldBe txnId
        debitResult.stateOfType[Crediting].amountAuthorized shouldBe transactionAmount
        debitResult.stateOfType[Crediting].captureAmount shouldBe transactionAmount
        debitResult.stateOfType[Crediting].debitHoldTimestamp shouldBe theTime

        val creditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToCredit, expectedCreditResultingBalance, theTime))
        creditResult.events shouldBe Seq(CreditSucceeded(expectedCreditResultingBalance))
        creditResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        creditResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posting].entryCode shouldBe entryCode
        creditResult.stateOfType[Posting].transactionId shouldBe txnId
        creditResult.stateOfType[Posting].amountAuthorized shouldBe transactionAmount
        creditResult.stateOfType[Posting].captureAmount shouldBe transactionAmount
        creditResult.stateOfType[Posting].debitHoldTimestamp shouldBe theTime

        val postingResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitPostedBalance, theTime))
        postingResult.events shouldBe Seq(DebitPostSucceeded(expectedDebitPostedBalance))
        postingResult.stateOfType[Posted].debitedAccountResultingBalance shouldBe expectedDebitPostedBalance
        postingResult.stateOfType[Posted].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        postingResult.stateOfType[Posted].accountToDebit shouldBe accountIdToDebit
        postingResult.stateOfType[Posted].accountToCredit shouldBe accountIdToCredit
        postingResult.stateOfType[Posted].entryCode shouldBe entryCode
        postingResult.stateOfType[Posted].transactionId shouldBe txnId
        postingResult.stateOfType[Posted].amountCaptured shouldBe transactionAmount

        val reverseResult = eventSourcedTestKit.runCommand(Reverse())
        reverseResult.events shouldBe Seq(ReversalRequested())
        reverseResult.stateOfType[RollingBackCredit].accountToDebit shouldBe accountIdToDebit
        reverseResult.stateOfType[RollingBackCredit].accountToCredit shouldBe accountIdToCredit
        reverseResult.stateOfType[RollingBackCredit].entryCode shouldBe entryCode
        reverseResult.stateOfType[RollingBackCredit].transactionId shouldBe txnId
        reverseResult.stateOfType[RollingBackCredit].creditedAmount shouldBe transactionAmount
        reverseResult.stateOfType[RollingBackCredit].amountCaptured shouldBe Some(transactionAmount)
        reverseResult.stateOfType[RollingBackCredit].code shouldBe None
      }

      "transition to RollingBackDebit on DebitAdjustmentDone" in {
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, Post(txnId, setupEntryCode, transactionAmount, BigDecimal(0), theTime)) once

          stubResultMessenger expects TransactionSuccessful(expectedDebitPostedBalance, expectedCreditResultingBalance) once

          stubAccountMessenger expects(accountIdToCredit, DebitAdjust(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, CreditAdjust(txnId, setupEntryCode, transactionAmount)) once
        }

        given()

        val reverseResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToCredit, expectedCreditResultingBalance, theTime))
        reverseResult.events shouldBe Seq(DebitAdjustmentDone(expectedCreditResultingBalance))
        reverseResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        reverseResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        reverseResult.stateOfType[RollingBackDebit].entryCode shouldBe entryCode
        reverseResult.stateOfType[RollingBackDebit].transactionId shouldBe txnId
        reverseResult.stateOfType[RollingBackDebit].authorizedAmount shouldBe transactionAmount
        reverseResult.stateOfType[RollingBackDebit].amountCaptured shouldBe Some(transactionAmount)
      }

      "remain in RollingBackCredit on DebitAdjustmentFailed and resume to RollingBackDebit" in {
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, Post(txnId, setupEntryCode, transactionAmount, BigDecimal(0), theTime)) once

          stubResultMessenger expects TransactionSuccessful(expectedDebitPostedBalance, expectedCreditResultingBalance) once

          stubAccountMessenger expects(accountIdToCredit, DebitAdjust(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, DebitAdjust(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, CreditAdjust(txnId, setupEntryCode, transactionAmount)) once
        }

        given()

        val reverseResult = eventSourcedTestKit.runCommand(RejectAccounting(accountIdToCredit, LedgerError.INSUFFICIENT_BALANCE))
        reverseResult.events shouldBe Seq(DebitAdjustmentFailed())
        reverseResult.stateOfType[RollingBackCredit].accountToDebit shouldBe accountIdToDebit
        reverseResult.stateOfType[RollingBackCredit].accountToCredit shouldBe accountIdToCredit
        reverseResult.stateOfType[RollingBackCredit].entryCode shouldBe entryCode
        reverseResult.stateOfType[RollingBackCredit].transactionId shouldBe txnId
        reverseResult.stateOfType[RollingBackCredit].creditedAmount shouldBe transactionAmount
        reverseResult.stateOfType[RollingBackCredit].amountCaptured shouldBe Some(transactionAmount)
        reverseResult.stateOfType[RollingBackCredit].code shouldBe None

        val resumeResult = eventSourcedTestKit.runCommand(Resume())
        resumeResult.hasNoEvents shouldBe true
        resumeResult.stateOfType[RollingBackCredit].accountToDebit shouldBe accountIdToDebit
        resumeResult.stateOfType[RollingBackCredit].accountToCredit shouldBe accountIdToCredit
        resumeResult.stateOfType[RollingBackCredit].entryCode shouldBe entryCode
        resumeResult.stateOfType[RollingBackCredit].transactionId shouldBe txnId
        resumeResult.stateOfType[RollingBackCredit].creditedAmount shouldBe transactionAmount
        resumeResult.stateOfType[RollingBackCredit].amountCaptured shouldBe Some(transactionAmount)
        resumeResult.stateOfType[RollingBackCredit].code shouldBe None

        val rollbackResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToCredit, expectedCreditResultingBalance, theTime))
        rollbackResult.events shouldBe Seq(DebitAdjustmentDone(expectedCreditResultingBalance))
        rollbackResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        rollbackResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        rollbackResult.stateOfType[RollingBackDebit].entryCode shouldBe entryCode
        rollbackResult.stateOfType[RollingBackDebit].transactionId shouldBe txnId
        rollbackResult.stateOfType[RollingBackDebit].authorizedAmount shouldBe transactionAmount
        rollbackResult.stateOfType[RollingBackDebit].amountCaptured shouldBe Some(transactionAmount)

      }
    }

    "RollingBackDebit due to Reversal" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))
      val expectedCreditResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4), BigDecimal(5))
      val expectedDebitPostedBalance: ResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

      def given(): Unit = {
        val beginResult = eventSourcedTestKit.runCommand(Begin(entryCode, accountIdToDebit, accountIdToCredit, transactionAmount))
        beginResult.events shouldBe Seq(Started(entryCode, accountIdToDebit, accountIdToCredit, transactionAmount, authOnly = false))
        beginResult.stateOfType[Authorizing].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Authorizing].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Authorizing].entryCode shouldBe entryCode
        beginResult.stateOfType[Authorizing].transactionId shouldBe txnId
        beginResult.stateOfType[Authorizing].amountAuthorized shouldBe transactionAmount

        val debitResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance, theTime))
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, theTime))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].entryCode shouldBe entryCode
        debitResult.stateOfType[Crediting].transactionId shouldBe txnId
        debitResult.stateOfType[Crediting].amountAuthorized shouldBe transactionAmount
        debitResult.stateOfType[Crediting].captureAmount shouldBe transactionAmount
        debitResult.stateOfType[Crediting].debitHoldTimestamp shouldBe theTime

        val creditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToCredit, expectedCreditResultingBalance, theTime))
        creditResult.events shouldBe Seq(CreditSucceeded(expectedCreditResultingBalance))
        creditResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        creditResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posting].entryCode shouldBe entryCode
        creditResult.stateOfType[Posting].transactionId shouldBe txnId
        creditResult.stateOfType[Posting].amountAuthorized shouldBe transactionAmount
        creditResult.stateOfType[Posting].captureAmount shouldBe transactionAmount
        creditResult.stateOfType[Posting].debitHoldTimestamp shouldBe theTime

        val postingResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitPostedBalance, theTime))
        postingResult.events shouldBe Seq(DebitPostSucceeded(expectedDebitPostedBalance))
        postingResult.stateOfType[Posted].debitedAccountResultingBalance shouldBe expectedDebitPostedBalance
        postingResult.stateOfType[Posted].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        postingResult.stateOfType[Posted].accountToDebit shouldBe accountIdToDebit
        postingResult.stateOfType[Posted].accountToCredit shouldBe accountIdToCredit
        postingResult.stateOfType[Posted].entryCode shouldBe entryCode
        postingResult.stateOfType[Posted].transactionId shouldBe txnId
        postingResult.stateOfType[Posted].amountCaptured shouldBe transactionAmount

        val reverseResult = eventSourcedTestKit.runCommand(Reverse())
        reverseResult.events shouldBe Seq(ReversalRequested())
        reverseResult.stateOfType[RollingBackCredit].accountToDebit shouldBe accountIdToDebit
        reverseResult.stateOfType[RollingBackCredit].accountToCredit shouldBe accountIdToCredit
        reverseResult.stateOfType[RollingBackCredit].entryCode shouldBe entryCode
        reverseResult.stateOfType[RollingBackCredit].transactionId shouldBe txnId
        reverseResult.stateOfType[RollingBackCredit].creditedAmount shouldBe transactionAmount
        reverseResult.stateOfType[RollingBackCredit].amountCaptured shouldBe Some(transactionAmount)
        reverseResult.stateOfType[RollingBackCredit].code shouldBe None

        val reverseCreditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToCredit, expectedCreditResultingBalance, theTime))
        reverseCreditResult.events shouldBe Seq(DebitAdjustmentDone(expectedCreditResultingBalance))
        reverseCreditResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        reverseCreditResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        reverseCreditResult.stateOfType[RollingBackDebit].entryCode shouldBe entryCode
        reverseCreditResult.stateOfType[RollingBackDebit].transactionId shouldBe txnId
        reverseCreditResult.stateOfType[RollingBackDebit].authorizedAmount shouldBe transactionAmount
        reverseCreditResult.stateOfType[RollingBackDebit].amountCaptured shouldBe Some(transactionAmount)
      }

      "transition to Reversed after CreditAdjustmentDone" in {
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, Post(txnId, setupEntryCode, transactionAmount, BigDecimal(0), theTime)) once

          stubResultMessenger expects TransactionSuccessful(expectedDebitPostedBalance, expectedCreditResultingBalance) once

          stubAccountMessenger expects(accountIdToCredit, DebitAdjust(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, CreditAdjust(txnId, setupEntryCode, transactionAmount)) once

          stubResultMessenger expects TransactionReversed() once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance, theTime))
        creditResult.events shouldBe Seq(CreditAdjustmentDone(expectedDebitResultingBalance))
        creditResult.stateOfType[Reversed].entryCode shouldBe entryCode
        creditResult.stateOfType[Reversed].transactionId shouldBe txnId
      }

      "remain in RollingBackDebit on CreditAdjustmentFailed then resumed to Failed" in {
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, Post(txnId, setupEntryCode, transactionAmount, BigDecimal(0), theTime)) once

          stubResultMessenger expects TransactionSuccessful(expectedDebitPostedBalance, expectedCreditResultingBalance) once

          stubAccountMessenger expects(accountIdToCredit, DebitAdjust(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, CreditAdjust(txnId, setupEntryCode, transactionAmount)) once

          stubAccountMessenger expects(accountIdToDebit, CreditAdjust(txnId, setupEntryCode, transactionAmount)) once

          stubResultMessenger expects TransactionReversed() once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(RejectAccounting(accountIdToDebit, LedgerError.INSUFFICIENT_BALANCE))
        creditResult.events shouldBe Seq(CreditAdjustmentFailed())
        creditResult.stateOfType[RollingBackDebit].code shouldBe None
        creditResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[RollingBackDebit].entryCode shouldBe entryCode
        creditResult.stateOfType[RollingBackDebit].transactionId shouldBe txnId
        creditResult.stateOfType[RollingBackDebit].authorizedAmount shouldBe transactionAmount
        creditResult.stateOfType[RollingBackDebit].amountCaptured shouldBe Some(transactionAmount)

        val resumeResult = eventSourcedTestKit.runCommand(Resume())
        resumeResult.hasNoEvents shouldBe true
        resumeResult.stateOfType[RollingBackDebit].code shouldBe None
        resumeResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        resumeResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        resumeResult.stateOfType[RollingBackDebit].entryCode shouldBe entryCode
        resumeResult.stateOfType[RollingBackDebit].transactionId shouldBe txnId
        resumeResult.stateOfType[RollingBackDebit].authorizedAmount shouldBe transactionAmount
        resumeResult.stateOfType[RollingBackDebit].amountCaptured shouldBe Some(transactionAmount)

        val rollbackResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance, theTime))
        rollbackResult.events shouldBe Seq(CreditAdjustmentDone(expectedDebitResultingBalance))
        rollbackResult.stateOfType[Reversed].entryCode shouldBe entryCode
        rollbackResult.stateOfType[Reversed].transactionId shouldBe txnId
      }
    }
    "Authorizing (auth only)" must {
      def given(): Unit = {
        val beginResult = eventSourcedTestKit.runCommand(Begin(entryCode, accountIdToDebit, accountIdToCredit, transactionAmount, authOnly = true))
        beginResult.events shouldBe Seq(Started(entryCode, accountIdToDebit, accountIdToCredit, transactionAmount, authOnly = true))
        beginResult.stateOfType[Authorizing].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Authorizing].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Authorizing].entryCode shouldBe entryCode
        beginResult.stateOfType[Authorizing].transactionId shouldBe txnId
        beginResult.stateOfType[Authorizing].amountAuthorized shouldBe transactionAmount
      }

      "transition to Pending after DebitHoldSucceeded" in {
        val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))

        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, setupEntryCode, transactionAmount)) once

          stubResultMessenger expects TransactionPending() once
        }

        given()

        val debitResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance, theTime))
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, theTime))
        debitResult.stateOfType[Pending].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Pending].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Pending].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Pending].entryCode shouldBe entryCode
        debitResult.stateOfType[Pending].transactionId shouldBe txnId
        debitResult.stateOfType[Pending].amountAuthorized shouldBe transactionAmount
        debitResult.stateOfType[Pending].debitHoldTimestamp shouldBe theTime
      }

      "transition to Failed after DebitHoldFailed" in {
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, setupEntryCode, transactionAmount)) once

          stubResultMessenger expects TransactionFailed(LedgerError.INSUFFICIENT_BALANCE) once
        }

        given()

        val debitResult = eventSourcedTestKit.runCommand(RejectAccounting(accountIdToDebit, LedgerError.INSUFFICIENT_BALANCE))
        debitResult.events shouldBe Seq(DebitHoldFailed(LedgerError.INSUFFICIENT_BALANCE))
        debitResult.stateOfType[Failed].code shouldBe LedgerError.INSUFFICIENT_BALANCE
        debitResult.stateOfType[Failed].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Failed].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Failed].entryCode shouldBe entryCode
        debitResult.stateOfType[Failed].transactionId shouldBe txnId
        debitResult.stateOfType[Failed].amount shouldBe transactionAmount
      }
    }

    "Pending" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))

      def given(): Unit = {
        val beginResult = eventSourcedTestKit.runCommand(Begin(entryCode, accountIdToDebit, accountIdToCredit, transactionAmount, authOnly = true))
        beginResult.events shouldBe Seq(Started(entryCode, accountIdToDebit, accountIdToCredit, transactionAmount, authOnly = true))
        beginResult.stateOfType[Authorizing].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Authorizing].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Authorizing].entryCode shouldBe entryCode
        beginResult.stateOfType[Authorizing].transactionId shouldBe txnId
        beginResult.stateOfType[Authorizing].amountAuthorized shouldBe transactionAmount

        val debitResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance, theTime))
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, theTime))
        debitResult.stateOfType[Pending].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Pending].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Pending].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Pending].entryCode shouldBe entryCode
        debitResult.stateOfType[Pending].transactionId shouldBe txnId
        debitResult.stateOfType[Pending].amountAuthorized shouldBe transactionAmount
        debitResult.stateOfType[Pending].debitHoldTimestamp shouldBe theTime
      }

      "transition to Crediting (partial) after CaptureRequested" in {
        val captureAmount: BigDecimal = BigDecimal(23)

        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, setupEntryCode, transactionAmount)) once

          stubResultMessenger expects TransactionPending() once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, setupEntryCode, captureAmount)) once
        }

        given()

        val pendingResult = eventSourcedTestKit.runCommand(Capture(captureAmount))
        pendingResult.events shouldBe Seq(CaptureRequested(captureAmount))
        pendingResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        pendingResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        pendingResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        pendingResult.stateOfType[Crediting].entryCode shouldBe entryCode
        pendingResult.stateOfType[Crediting].transactionId shouldBe txnId
        pendingResult.stateOfType[Crediting].amountAuthorized shouldBe transactionAmount
        pendingResult.stateOfType[Crediting].captureAmount shouldBe captureAmount
        pendingResult.stateOfType[Crediting].debitHoldTimestamp shouldBe theTime
      }

      "transition to Reversed after ReverseRequested" in {

        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, setupEntryCode, transactionAmount)) once

          stubResultMessenger expects TransactionPending() once

          stubAccountMessenger expects(accountIdToDebit, Release(txnId, setupEntryCode, transactionAmount)) once

        }

        given()

        val debitResult = eventSourcedTestKit.runCommand(Reverse())
        debitResult.events shouldBe Seq(ReversalRequested())
        debitResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[RollingBackDebit].entryCode shouldBe entryCode
        debitResult.stateOfType[RollingBackDebit].transactionId shouldBe txnId
        debitResult.stateOfType[RollingBackDebit].authorizedAmount shouldBe transactionAmount
        debitResult.stateOfType[RollingBackDebit].amountCaptured shouldBe None
        debitResult.stateOfType[RollingBackDebit].code shouldBe None
      }

      "do nothing on over-Capture" in {
        val captureAmount: BigDecimal = BigDecimal(100.00001)

        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, setupEntryCode, transactionAmount)) once

          stubResultMessenger expects TransactionPending() once

          stubResultMessenger expects CaptureRejected(LedgerError.CAPTURE_MORE_THAN_AUTHORIZED) once
        }

        given()

        val pendingResult = eventSourcedTestKit.runCommand(Capture(captureAmount))
        pendingResult.hasNoEvents shouldBe true
        // Nothing should change
        pendingResult.stateOfType[Pending].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        pendingResult.stateOfType[Pending].accountToDebit shouldBe accountIdToDebit
        pendingResult.stateOfType[Pending].accountToCredit shouldBe accountIdToCredit
        pendingResult.stateOfType[Pending].entryCode shouldBe entryCode
        pendingResult.stateOfType[Pending].transactionId shouldBe txnId
        pendingResult.stateOfType[Pending].amountAuthorized shouldBe transactionAmount
        pendingResult.stateOfType[Pending].debitHoldTimestamp shouldBe theTime
      }
    }

    "Crediting (partial)" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))
      val holdTime = DateUtils.now().minus(Duration.ofDays(1))
      val captureAmount: BigDecimal = BigDecimal(23)

      def given(): Unit = {
        val beginResult = eventSourcedTestKit.runCommand(Begin(entryCode, accountIdToDebit, accountIdToCredit, transactionAmount, authOnly = true))
        beginResult.events shouldBe Seq(Started(entryCode, accountIdToDebit, accountIdToCredit, transactionAmount, authOnly = true))
        beginResult.stateOfType[Authorizing].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Authorizing].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Authorizing].entryCode shouldBe entryCode
        beginResult.stateOfType[Authorizing].transactionId shouldBe txnId
        beginResult.stateOfType[Authorizing].amountAuthorized shouldBe transactionAmount

        val debitResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance, holdTime))
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, holdTime))
        debitResult.stateOfType[Pending].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Pending].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Pending].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Pending].entryCode shouldBe entryCode
        debitResult.stateOfType[Pending].transactionId shouldBe txnId
        debitResult.stateOfType[Pending].amountAuthorized shouldBe transactionAmount
        debitResult.stateOfType[Pending].debitHoldTimestamp shouldBe holdTime

        val pendingResult = eventSourcedTestKit.runCommand(Capture(captureAmount))
        pendingResult.events shouldBe Seq(CaptureRequested(captureAmount))
        pendingResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        pendingResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        pendingResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        pendingResult.stateOfType[Crediting].entryCode shouldBe entryCode
        pendingResult.stateOfType[Crediting].transactionId shouldBe txnId
        pendingResult.stateOfType[Crediting].amountAuthorized shouldBe transactionAmount
        pendingResult.stateOfType[Crediting].captureAmount shouldBe captureAmount
        pendingResult.stateOfType[Crediting].debitHoldTimestamp shouldBe holdTime
      }

      "transition to Posting after CreditingSucceeded" in {
        val expectedCreditResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4), BigDecimal(5))
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, setupEntryCode, transactionAmount)) once

          stubResultMessenger expects TransactionPending() once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, setupEntryCode, captureAmount)) once

          stubAccountMessenger expects(accountIdToDebit, Post(txnId, setupEntryCode, captureAmount, transactionAmount - captureAmount, holdTime)) once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToCredit, expectedCreditResultingBalance, theTime))
        creditResult.events shouldBe Seq(CreditSucceeded(expectedCreditResultingBalance))
        creditResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        creditResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posting].entryCode shouldBe entryCode
        creditResult.stateOfType[Posting].transactionId shouldBe txnId
        creditResult.stateOfType[Posting].amountAuthorized shouldBe transactionAmount
        creditResult.stateOfType[Posting].captureAmount shouldBe captureAmount
        creditResult.stateOfType[Posting].debitHoldTimestamp shouldBe holdTime

      }

      "transition to RollingBackDebit after CreditingFailed" in {
        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, setupEntryCode, transactionAmount)) once

          stubResultMessenger expects TransactionPending() once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, setupEntryCode, captureAmount)) once

          stubAccountMessenger expects(accountIdToDebit, Release(txnId, setupEntryCode, transactionAmount)) once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(RejectAccounting(accountIdToCredit, LedgerError.INSUFFICIENT_BALANCE))
        creditResult.events shouldBe Seq(CreditFailed(LedgerError.INSUFFICIENT_BALANCE))
        creditResult.stateOfType[RollingBackDebit].code shouldBe Some(LedgerError.INSUFFICIENT_BALANCE)
        creditResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[RollingBackDebit].entryCode shouldBe entryCode
        creditResult.stateOfType[RollingBackDebit].transactionId shouldBe txnId
        creditResult.stateOfType[RollingBackDebit].authorizedAmount shouldBe transactionAmount
        creditResult.stateOfType[RollingBackDebit].amountCaptured shouldBe None
      }
    }

    "Posting (partial)" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))
      val expectedCreditResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4), BigDecimal(5))
      val holdTime = DateUtils.now().minus(Duration.ofDays(1))
      val captureAmount: BigDecimal = BigDecimal(23)

      def given(): Unit = {
        val beginResult = eventSourcedTestKit.runCommand(Begin(entryCode, accountIdToDebit, accountIdToCredit, transactionAmount, authOnly = true))
        beginResult.events shouldBe Seq(Started(entryCode, accountIdToDebit, accountIdToCredit, transactionAmount, authOnly = true))
        beginResult.stateOfType[Authorizing].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Authorizing].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Authorizing].entryCode shouldBe entryCode
        beginResult.stateOfType[Authorizing].transactionId shouldBe txnId
        beginResult.stateOfType[Authorizing].amountAuthorized shouldBe transactionAmount

        val debitResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, expectedDebitResultingBalance, holdTime))
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, holdTime))
        debitResult.stateOfType[Pending].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Pending].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Pending].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Pending].entryCode shouldBe entryCode
        debitResult.stateOfType[Pending].transactionId shouldBe txnId
        debitResult.stateOfType[Pending].amountAuthorized shouldBe transactionAmount
        debitResult.stateOfType[Pending].debitHoldTimestamp shouldBe holdTime

        val pendingResult = eventSourcedTestKit.runCommand(Capture(captureAmount))
        pendingResult.events shouldBe Seq(CaptureRequested(captureAmount))
        pendingResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        pendingResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        pendingResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        pendingResult.stateOfType[Crediting].entryCode shouldBe entryCode
        pendingResult.stateOfType[Crediting].transactionId shouldBe txnId
        pendingResult.stateOfType[Crediting].amountAuthorized shouldBe transactionAmount
        pendingResult.stateOfType[Crediting].captureAmount shouldBe captureAmount
        pendingResult.stateOfType[Crediting].debitHoldTimestamp shouldBe holdTime

        val creditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToCredit, expectedCreditResultingBalance, theTime))
        creditResult.events shouldBe Seq(CreditSucceeded(expectedCreditResultingBalance))
        creditResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        creditResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posting].entryCode shouldBe entryCode
        creditResult.stateOfType[Posting].transactionId shouldBe txnId
        creditResult.stateOfType[Posting].amountAuthorized shouldBe transactionAmount
        creditResult.stateOfType[Posting].captureAmount shouldBe captureAmount
        creditResult.stateOfType[Posting].debitHoldTimestamp shouldBe holdTime
      }

      "transition to Posted after DebitPostSucceeded" in {
        val debitPostedAccountResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, setupEntryCode, transactionAmount)) once

          stubResultMessenger expects TransactionPending() once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, setupEntryCode, captureAmount)) once

          stubAccountMessenger expects(accountIdToDebit, Post(txnId, setupEntryCode, captureAmount, transactionAmount - captureAmount, holdTime)) once

          stubResultMessenger expects TransactionSuccessful(debitPostedAccountResultingBalance, expectedCreditResultingBalance) once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, debitPostedAccountResultingBalance, theTime))
        creditResult.events shouldBe Seq(DebitPostSucceeded(debitPostedAccountResultingBalance))
        creditResult.stateOfType[Posted].debitedAccountResultingBalance shouldBe debitPostedAccountResultingBalance
        creditResult.stateOfType[Posted].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posted].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posted].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posted].entryCode shouldBe entryCode
        creditResult.stateOfType[Posted].transactionId shouldBe txnId
        creditResult.stateOfType[Posted].amountCaptured shouldBe captureAmount
      }

      "remain in Posting after DebitPostFailed and can be resumed to Posted" in {
        val debitPostedAccountResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

        inSequence {
          stubAccountMessenger expects(accountIdToDebit, DebitHold(txnId, setupEntryCode, transactionAmount)) once

          stubResultMessenger expects TransactionPending() once

          stubAccountMessenger expects(accountIdToCredit, Credit(txnId, setupEntryCode, captureAmount)) once

          stubAccountMessenger expects(accountIdToDebit, Post(txnId, setupEntryCode, captureAmount, transactionAmount - captureAmount, holdTime)) once //"twice" doesn't work, strangely

          stubAccountMessenger expects(accountIdToDebit, Post(txnId, setupEntryCode, captureAmount, transactionAmount - captureAmount, holdTime)) once

          stubResultMessenger expects TransactionSuccessful(debitPostedAccountResultingBalance, expectedCreditResultingBalance) once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(RejectAccounting(accountIdToDebit, LedgerError.INSUFFICIENT_BALANCE))
        creditResult.events shouldBe Seq(DebitPostFailed())
        creditResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        creditResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posting].entryCode shouldBe entryCode
        creditResult.stateOfType[Posting].transactionId shouldBe txnId
        creditResult.stateOfType[Posting].amountAuthorized shouldBe transactionAmount
        creditResult.stateOfType[Posting].captureAmount shouldBe captureAmount
        creditResult.stateOfType[Posting].debitHoldTimestamp shouldBe holdTime

        val resumeResult = eventSourcedTestKit.runCommand(Resume())
        resumeResult.hasNoEvents shouldBe true
        resumeResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        resumeResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        resumeResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        resumeResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        resumeResult.stateOfType[Posting].entryCode shouldBe entryCode
        resumeResult.stateOfType[Posting].transactionId shouldBe txnId
        resumeResult.stateOfType[Posting].amountAuthorized shouldBe transactionAmount
        resumeResult.stateOfType[Posting].captureAmount shouldBe captureAmount
        resumeResult.stateOfType[Posting].debitHoldTimestamp shouldBe holdTime

        val postingResult = eventSourcedTestKit.runCommand(AcceptAccounting(accountIdToDebit, debitPostedAccountResultingBalance, theTime))
        postingResult.events shouldBe Seq(DebitPostSucceeded(debitPostedAccountResultingBalance))
        postingResult.stateOfType[Posted].debitedAccountResultingBalance shouldBe debitPostedAccountResultingBalance
        postingResult.stateOfType[Posted].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        postingResult.stateOfType[Posted].accountToDebit shouldBe accountIdToDebit
        postingResult.stateOfType[Posted].accountToCredit shouldBe accountIdToCredit
        postingResult.stateOfType[Posted].entryCode shouldBe entryCode
        postingResult.stateOfType[Posted].transactionId shouldBe txnId
        postingResult.stateOfType[Posted].amountCaptured shouldBe captureAmount
      }
    }
  }

}
