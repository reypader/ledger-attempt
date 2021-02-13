package io.openledger.domain.entry

import akka.Done
import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.typesafe.config.ConfigFactory
import io.openledger.domain.account.Account._
import io.openledger.domain.entry.Entry.{Ack, Get, apply => _, _}
import io.openledger.domain.entry.states._
import io.openledger.events._
import io.openledger.{AccountingMode, DateUtils, LedgerError, ResultingBalance}
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Duration
import java.util.UUID
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class EntrySpec
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

  private val stubAccountMessenger = mockFunction[String, AccountingCommand, Unit]
  private val stubResultMessenger = mockFunction[EntryResult, Unit]
  private val txnId = UUID.randomUUID().toString
  private val entryCode = "ENTRY"
  private val ackProbe = testKit.createTestProbe[TxnAck]
  private val accountIdToDebit = UUID.randomUUID().toString
  private val accountIdToCredit = UUID.randomUUID().toString
  private val eventSourcedTestKit = EventSourcedBehaviorTestKit[EntryCommand, EntryEvent, EntryState](
    system,
    Entry(txnId)(stubAccountMessenger, stubResultMessenger)
  )
  private val entryAmount = BigDecimal(100)
  private val theTime = DateUtils.now()

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    eventSourcedTestKit.clear()
  }

  "A Entry" when {
    val debitHold = DebitHold(txnId, entryCode, entryAmount)
    val fullCredit = Credit(txnId, entryCode, entryAmount)
    val fullPost = Post(txnId, entryCode, entryAmount, BigDecimal(0), theTime)
    val fullRelease = Release(txnId, entryCode, entryAmount)
    val debitAdjust = DebitAdjust(txnId, entryCode, entryAmount)
    val creditAdjust = CreditAdjust(txnId, entryCode, entryAmount)

    "Ready" must {
      "transition to Authorizing after Started" in {
        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once
        }
        val beginResult = eventSourcedTestKit.runCommand(
          Begin(entryCode, accountIdToDebit, accountIdToCredit, entryAmount, ackProbe.ref)
        )
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        beginResult.events shouldBe Seq(
          Started(entryCode, accountIdToDebit, accountIdToCredit, entryAmount, authOnly = false)
        )
        beginResult.stateOfType[Authorizing].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Authorizing].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Authorizing].entryCode shouldBe entryCode
        beginResult.stateOfType[Authorizing].entryId shouldBe txnId
        beginResult.stateOfType[Authorizing].reversalPending shouldBe false
        beginResult.stateOfType[Authorizing].amountAuthorized shouldBe entryAmount
      }

      "transition to Adjusting after AdjustRequested (debit)" in {
        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitAdjust) once
        }
        val beginResult = eventSourcedTestKit.runCommand(
          Adjust(entryCode, accountIdToDebit, entryAmount, AccountingMode.DEBIT, ackProbe.ref)
        )
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        beginResult.events shouldBe Seq(AdjustRequested(entryCode, accountIdToDebit, entryAmount, AccountingMode.DEBIT))
        beginResult.stateOfType[Adjusting].accountToAdjust shouldBe accountIdToDebit
        beginResult.stateOfType[Adjusting].mode shouldBe AccountingMode.DEBIT
        beginResult.stateOfType[Adjusting].entryCode shouldBe entryCode
        beginResult.stateOfType[Adjusting].entryId shouldBe txnId
        beginResult.stateOfType[Adjusting].amount shouldBe entryAmount
      }

      "transition to Adjusting after AdjustRequested (credit)" in {
        inSequence {
          stubAccountMessenger expects (accountIdToCredit, creditAdjust) once
        }
        val beginResult = eventSourcedTestKit.runCommand(
          Adjust(entryCode, accountIdToCredit, entryAmount, AccountingMode.CREDIT, ackProbe.ref)
        )
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        beginResult.events shouldBe Seq(
          AdjustRequested(entryCode, accountIdToCredit, entryAmount, AccountingMode.CREDIT)
        )
        beginResult.stateOfType[Adjusting].accountToAdjust shouldBe accountIdToCredit
        beginResult.stateOfType[Adjusting].mode shouldBe AccountingMode.CREDIT
        beginResult.stateOfType[Adjusting].entryCode shouldBe entryCode
        beginResult.stateOfType[Adjusting].entryId shouldBe txnId
        beginResult.stateOfType[Adjusting].amount shouldBe entryAmount
      }
    }

    "Adjusting (credit)" must {
      def given(): EntryState = {
        val beginResult = eventSourcedTestKit.runCommand(
          Adjust(entryCode, accountIdToCredit, entryAmount, AccountingMode.CREDIT, ackProbe.ref)
        )
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        beginResult.events shouldBe Seq(
          AdjustRequested(entryCode, accountIdToCredit, entryAmount, AccountingMode.CREDIT)
        )
        beginResult.stateOfType[Adjusting].accountToAdjust shouldBe accountIdToCredit
        beginResult.stateOfType[Adjusting].mode shouldBe AccountingMode.CREDIT
        beginResult.stateOfType[Adjusting].entryCode shouldBe entryCode
        beginResult.stateOfType[Adjusting].entryId shouldBe txnId
        beginResult.stateOfType[Adjusting].amount shouldBe entryAmount

        beginResult.stateOfType[Adjusting]
      }

      "transition to Adjusted on CreditAdjustmentDone" in {
        val expectedAdjustmentBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))

        inSequence {
          stubAccountMessenger expects (accountIdToCredit, creditAdjust) once

          stubResultMessenger expects AdjustmentSuccessful(txnId, AccountingMode.CREDIT, expectedAdjustmentBalance) once
        }

        given()

        val adjustResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(creditAdjust.hashCode(), accountIdToCredit, expectedAdjustmentBalance, theTime)
        )
        adjustResult.events shouldBe Seq(CreditAdjustmentDone(expectedAdjustmentBalance))
        adjustResult.stateOfType[Adjusted].accountToAdjust shouldBe accountIdToCredit
        adjustResult.stateOfType[Adjusted].entryCode shouldBe entryCode
        adjustResult.stateOfType[Adjusted].entryId shouldBe txnId
        adjustResult.stateOfType[Adjusted].amount shouldBe entryAmount
        adjustResult.stateOfType[Adjusted].mode shouldBe AccountingMode.CREDIT
        adjustResult.stateOfType[Adjusted].resultingBalance shouldBe expectedAdjustmentBalance
      }

      "transition to Failed on CreditAdjustmentFailed" in {
        inSequence {
          stubAccountMessenger expects (accountIdToCredit, creditAdjust) once

          stubResultMessenger expects (EntryFailed(
            txnId,
            LedgerError.UNSUPPORTED_ACCOUNT_OPERATION_ON_CURRENT_STATE
          )) once
        }

        given()

        val adjustResult = eventSourcedTestKit.runCommand(
          RejectAccounting(
            creditAdjust.hashCode(),
            accountIdToCredit,
            LedgerError.UNSUPPORTED_ACCOUNT_OPERATION_ON_CURRENT_STATE
          )
        )
        adjustResult.events shouldBe Seq(
          CreditAdjustmentFailed(LedgerError.UNSUPPORTED_ACCOUNT_OPERATION_ON_CURRENT_STATE.toString)
        )
        adjustResult.stateOfType[Failed].entryCode shouldBe entryCode
        adjustResult.stateOfType[Failed].entryId shouldBe txnId
        adjustResult
          .stateOfType[Failed]
          .code shouldBe LedgerError.UNSUPPORTED_ACCOUNT_OPERATION_ON_CURRENT_STATE.toString
      }
    }

    "Adjusting (debit)" must {
      def given(): EntryState = {
        val beginResult = eventSourcedTestKit.runCommand(
          Adjust(entryCode, accountIdToDebit, entryAmount, AccountingMode.DEBIT, ackProbe.ref)
        )
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        beginResult.events shouldBe Seq(AdjustRequested(entryCode, accountIdToDebit, entryAmount, AccountingMode.DEBIT))
        beginResult.stateOfType[Adjusting].accountToAdjust shouldBe accountIdToDebit
        beginResult.stateOfType[Adjusting].mode shouldBe AccountingMode.DEBIT
        beginResult.stateOfType[Adjusting].entryCode shouldBe entryCode
        beginResult.stateOfType[Adjusting].entryId shouldBe txnId
        beginResult.stateOfType[Adjusting].amount shouldBe entryAmount

        beginResult.stateOfType[Adjusting]
      }

      "transition to Adjusted on DebitAdjustmentDone" in {
        val expectedAdjustmentBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))

        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitAdjust) once

          stubResultMessenger expects AdjustmentSuccessful(txnId, AccountingMode.DEBIT, expectedAdjustmentBalance) once
        }

        given()

        val adjustResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(debitAdjust.hashCode(), accountIdToDebit, expectedAdjustmentBalance, theTime)
        )
        adjustResult.events shouldBe Seq(DebitAdjustmentDone(expectedAdjustmentBalance))
        adjustResult.stateOfType[Adjusted].accountToAdjust shouldBe accountIdToDebit
        adjustResult.stateOfType[Adjusted].entryCode shouldBe entryCode
        adjustResult.stateOfType[Adjusted].entryId shouldBe txnId
        adjustResult.stateOfType[Adjusted].amount shouldBe entryAmount
        adjustResult.stateOfType[Adjusted].mode shouldBe AccountingMode.DEBIT
        adjustResult.stateOfType[Adjusted].resultingBalance shouldBe expectedAdjustmentBalance
      }

      "transition to Failed on DebitAdjustmentFailed" in {
        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitAdjust) once

          stubResultMessenger expects (EntryFailed(
            txnId,
            LedgerError.UNSUPPORTED_ACCOUNT_OPERATION_ON_CURRENT_STATE
          )) once
        }

        given()

        val adjustResult = eventSourcedTestKit.runCommand(
          RejectAccounting(
            debitAdjust.hashCode(),
            accountIdToDebit,
            LedgerError.UNSUPPORTED_ACCOUNT_OPERATION_ON_CURRENT_STATE
          )
        )
        adjustResult.events shouldBe Seq(
          DebitAdjustmentFailed(LedgerError.UNSUPPORTED_ACCOUNT_OPERATION_ON_CURRENT_STATE.toString)
        )
        adjustResult.stateOfType[Failed].entryCode shouldBe entryCode
        adjustResult.stateOfType[Failed].entryId shouldBe txnId
        adjustResult
          .stateOfType[Failed]
          .code shouldBe LedgerError.UNSUPPORTED_ACCOUNT_OPERATION_ON_CURRENT_STATE.toString
      }
    }

    "Authorizing" must {
      def given(): EntryState = {
        val beginResult = eventSourcedTestKit.runCommand(
          Begin(entryCode, accountIdToDebit, accountIdToCredit, entryAmount, ackProbe.ref)
        )
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        beginResult.events shouldBe Seq(
          Started(entryCode, accountIdToDebit, accountIdToCredit, entryAmount, authOnly = false)
        )
        beginResult.stateOfType[Authorizing].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Authorizing].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Authorizing].entryCode shouldBe entryCode
        beginResult.stateOfType[Authorizing].entryId shouldBe txnId
        beginResult.stateOfType[Authorizing].reversalPending shouldBe false
        beginResult.stateOfType[Authorizing].amountAuthorized shouldBe entryAmount

        beginResult.stateOfType[Authorizing]
      }

      "transition to Crediting after DebitHoldSucceeded" in {
        val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))
        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubAccountMessenger expects (accountIdToCredit, fullCredit) once
        }

        given()

        val debitResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(debitHold.hashCode(), accountIdToDebit, expectedDebitResultingBalance, theTime)
        )
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, theTime))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].entryCode shouldBe entryCode
        debitResult.stateOfType[Crediting].entryId shouldBe txnId
        debitResult.stateOfType[Crediting].reversalPending shouldBe false
        debitResult.stateOfType[Crediting].amountAuthorized shouldBe entryAmount
        debitResult.stateOfType[Crediting].captureAmount shouldBe entryAmount
        debitResult.stateOfType[Crediting].debitHoldTimestamp shouldBe theTime
      }

      "transition to Failed after DebitHoldFailed" in {
        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubResultMessenger expects EntryFailed(txnId, LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE) once
        }

        given()

        val debitResult = eventSourcedTestKit.runCommand(
          RejectAccounting(debitHold.hashCode(), accountIdToDebit, LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE)
        )
        debitResult.events shouldBe Seq(DebitHoldFailed(LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString))
        debitResult.stateOfType[Failed].code shouldBe LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString

        debitResult.stateOfType[Failed].entryCode shouldBe entryCode
        debitResult.stateOfType[Failed].entryId shouldBe txnId

      }

      "transition to Crediting after DebitHoldSucceeded (reversal marked)" in {
        val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))
        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubAccountMessenger expects (accountIdToCredit, fullCredit) once
        }

        given()

        val reverseResult = eventSourcedTestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        reverseResult.events shouldBe Seq(ReversalRequested())
        reverseResult.stateOfType[Authorizing].accountToDebit shouldBe accountIdToDebit
        reverseResult.stateOfType[Authorizing].accountToCredit shouldBe accountIdToCredit
        reverseResult.stateOfType[Authorizing].entryCode shouldBe entryCode
        reverseResult.stateOfType[Authorizing].entryId shouldBe txnId
        reverseResult.stateOfType[Authorizing].reversalPending shouldBe true
        reverseResult.stateOfType[Authorizing].amountAuthorized shouldBe entryAmount

        val debitResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(debitHold.hashCode(), accountIdToDebit, expectedDebitResultingBalance, theTime)
        )
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, theTime))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].entryCode shouldBe entryCode
        debitResult.stateOfType[Crediting].entryId shouldBe txnId
        debitResult.stateOfType[Crediting].reversalPending shouldBe true
        debitResult.stateOfType[Crediting].amountAuthorized shouldBe entryAmount
        debitResult.stateOfType[Crediting].captureAmount shouldBe entryAmount
        debitResult.stateOfType[Crediting].debitHoldTimestamp shouldBe theTime
      }

      "transition to Failed after DebitHoldFailed (reversal marked)" in {
        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubResultMessenger expects EntryFailed(txnId, LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE) once
        }

        given()

        val reverseResult = eventSourcedTestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        reverseResult.events shouldBe Seq(ReversalRequested())
        reverseResult.stateOfType[Authorizing].accountToDebit shouldBe accountIdToDebit
        reverseResult.stateOfType[Authorizing].accountToCredit shouldBe accountIdToCredit
        reverseResult.stateOfType[Authorizing].entryCode shouldBe entryCode
        reverseResult.stateOfType[Authorizing].entryId shouldBe txnId
        reverseResult.stateOfType[Authorizing].reversalPending shouldBe true
        reverseResult.stateOfType[Authorizing].amountAuthorized shouldBe entryAmount

        val debitResult = eventSourcedTestKit.runCommand(
          RejectAccounting(debitHold.hashCode(), accountIdToDebit, LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE)
        )
        debitResult.events shouldBe Seq(DebitHoldFailed(LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString))
        debitResult.stateOfType[Failed].code shouldBe LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString
        debitResult.stateOfType[Failed].entryCode shouldBe entryCode
        debitResult.stateOfType[Failed].entryId shouldBe txnId

      }
    }

    "Crediting" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))
      val holdTime = DateUtils.now().minus(Duration.ofDays(1))
      val fullPastPost = Post(txnId, entryCode, entryAmount, BigDecimal(0), holdTime)

      def given(): EntryState = {
        val beginResult = eventSourcedTestKit.runCommand(
          Begin(entryCode, accountIdToDebit, accountIdToCredit, entryAmount, ackProbe.ref)
        )
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        beginResult.events shouldBe Seq(
          Started(entryCode, accountIdToDebit, accountIdToCredit, entryAmount, authOnly = false)
        )
        beginResult.stateOfType[Authorizing].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Authorizing].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Authorizing].entryCode shouldBe entryCode
        beginResult.stateOfType[Authorizing].entryId shouldBe txnId
        beginResult.stateOfType[Authorizing].reversalPending shouldBe false
        beginResult.stateOfType[Authorizing].amountAuthorized shouldBe entryAmount

        val debitResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(debitHold.hashCode(), accountIdToDebit, expectedDebitResultingBalance, holdTime)
        )
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, holdTime))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].entryCode shouldBe entryCode
        debitResult.stateOfType[Crediting].entryId shouldBe txnId
        debitResult.stateOfType[Crediting].reversalPending shouldBe false
        debitResult.stateOfType[Crediting].amountAuthorized shouldBe entryAmount
        debitResult.stateOfType[Crediting].captureAmount shouldBe entryAmount
        debitResult.stateOfType[Crediting].debitHoldTimestamp shouldBe holdTime

        debitResult.stateOfType[Crediting]
      }

      "transition to Posting after CreditingSucceeded" in {
        val expectedCreditResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4), BigDecimal(5))
        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubAccountMessenger expects (accountIdToCredit, fullCredit) once

          stubAccountMessenger expects (accountIdToDebit, fullPastPost) once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(fullCredit.hashCode(), accountIdToCredit, expectedCreditResultingBalance, theTime)
        )
        creditResult.events shouldBe Seq(CreditSucceeded(expectedCreditResultingBalance))
        creditResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        creditResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posting].entryCode shouldBe entryCode
        creditResult.stateOfType[Posting].entryId shouldBe txnId
        creditResult.stateOfType[Posting].reversalPending shouldBe false
        creditResult.stateOfType[Posting].amountAuthorized shouldBe entryAmount
        creditResult.stateOfType[Posting].captureAmount shouldBe entryAmount
        creditResult.stateOfType[Posting].debitHoldTimestamp shouldBe holdTime

      }

      "transition to RollingBackDebit after CreditingFailed" in {
        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubAccountMessenger expects (accountIdToCredit, fullCredit) once

          stubAccountMessenger expects (accountIdToDebit, fullRelease) once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(
          RejectAccounting(fullCredit.hashCode(), accountIdToCredit, LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE)
        )
        creditResult.events shouldBe Seq(CreditFailed(LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString))
        creditResult.stateOfType[RollingBackDebit].code shouldBe Some(
          LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString
        )
        creditResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[RollingBackDebit].entryCode shouldBe entryCode
        creditResult.stateOfType[RollingBackDebit].entryId shouldBe txnId
        creditResult.stateOfType[RollingBackDebit].authorizedAmount shouldBe entryAmount
        creditResult.stateOfType[RollingBackDebit].amountCaptured shouldBe None
        creditResult.stateOfType[RollingBackDebit].creditReversedResultingBalance shouldBe None
      }

      "transition to Posting after CreditingSucceeded (reversal marked)" in {
        val expectedCreditResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4), BigDecimal(5))
        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubAccountMessenger expects (accountIdToCredit, fullCredit) once

          stubAccountMessenger expects (accountIdToDebit, fullPastPost) once
        }

        given()

        val reverseResult = eventSourcedTestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        reverseResult.events shouldBe Seq(ReversalRequested())
        reverseResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        reverseResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        reverseResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        reverseResult.stateOfType[Crediting].entryCode shouldBe entryCode
        reverseResult.stateOfType[Crediting].entryId shouldBe txnId
        reverseResult.stateOfType[Crediting].reversalPending shouldBe true
        reverseResult.stateOfType[Crediting].amountAuthorized shouldBe entryAmount
        reverseResult.stateOfType[Crediting].captureAmount shouldBe entryAmount
        reverseResult.stateOfType[Crediting].debitHoldTimestamp shouldBe holdTime

        val creditResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(fullCredit.hashCode(), accountIdToCredit, expectedCreditResultingBalance, theTime)
        )
        creditResult.events shouldBe Seq(CreditSucceeded(expectedCreditResultingBalance))
        creditResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        creditResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posting].entryCode shouldBe entryCode
        creditResult.stateOfType[Posting].entryId shouldBe txnId
        creditResult.stateOfType[Posting].reversalPending shouldBe true
        creditResult.stateOfType[Posting].amountAuthorized shouldBe entryAmount
        creditResult.stateOfType[Posting].captureAmount shouldBe entryAmount
        creditResult.stateOfType[Posting].debitHoldTimestamp shouldBe holdTime

      }

      "transition to RollingBackDebit after CreditingFailed (reversal marked)" in {
        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubAccountMessenger expects (accountIdToCredit, fullCredit) once

          stubAccountMessenger expects (accountIdToDebit, fullRelease) once
        }

        given()

        val reverseResult = eventSourcedTestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        reverseResult.events shouldBe Seq(ReversalRequested())
        reverseResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        reverseResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        reverseResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        reverseResult.stateOfType[Crediting].entryCode shouldBe entryCode
        reverseResult.stateOfType[Crediting].entryId shouldBe txnId
        reverseResult.stateOfType[Crediting].reversalPending shouldBe true
        reverseResult.stateOfType[Crediting].amountAuthorized shouldBe entryAmount
        reverseResult.stateOfType[Crediting].captureAmount shouldBe entryAmount
        reverseResult.stateOfType[Crediting].debitHoldTimestamp shouldBe holdTime

        val creditResult = eventSourcedTestKit.runCommand(
          RejectAccounting(fullCredit.hashCode(), accountIdToCredit, LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE)
        )
        creditResult.events shouldBe Seq(CreditFailed(LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString))
        creditResult.stateOfType[RollingBackDebit].code shouldBe Some(
          LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString
        )
        creditResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[RollingBackDebit].entryCode shouldBe entryCode
        creditResult.stateOfType[RollingBackDebit].entryId shouldBe txnId
        creditResult.stateOfType[RollingBackDebit].authorizedAmount shouldBe entryAmount
        creditResult.stateOfType[RollingBackDebit].amountCaptured shouldBe None
        creditResult.stateOfType[RollingBackDebit].creditReversedResultingBalance shouldBe None
      }
    }

    "RollingBackDebit due to Error" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))

      def given(): EntryState = {
        val beginResult = eventSourcedTestKit.runCommand(
          Begin(entryCode, accountIdToDebit, accountIdToCredit, entryAmount, ackProbe.ref)
        )
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        beginResult.events shouldBe Seq(
          Started(entryCode, accountIdToDebit, accountIdToCredit, entryAmount, authOnly = false)
        )
        beginResult.stateOfType[Authorizing].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Authorizing].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Authorizing].entryCode shouldBe entryCode
        beginResult.stateOfType[Authorizing].entryId shouldBe txnId
        beginResult.stateOfType[Authorizing].reversalPending shouldBe false
        beginResult.stateOfType[Authorizing].amountAuthorized shouldBe entryAmount

        val debitResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(debitHold.hashCode(), accountIdToDebit, expectedDebitResultingBalance, theTime)
        )
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, theTime))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].entryCode shouldBe entryCode
        debitResult.stateOfType[Crediting].entryId shouldBe txnId
        debitResult.stateOfType[Crediting].reversalPending shouldBe false
        debitResult.stateOfType[Crediting].amountAuthorized shouldBe entryAmount
        debitResult.stateOfType[Crediting].captureAmount shouldBe entryAmount
        debitResult.stateOfType[Crediting].debitHoldTimestamp shouldBe theTime

        val creditResult = eventSourcedTestKit.runCommand(
          RejectAccounting(fullCredit.hashCode(), accountIdToCredit, LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE)
        )
        creditResult.events shouldBe Seq(CreditFailed(LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString))
        creditResult.stateOfType[RollingBackDebit].code shouldBe Some(
          LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString
        )
        creditResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[RollingBackDebit].entryCode shouldBe entryCode
        creditResult.stateOfType[RollingBackDebit].entryId shouldBe txnId
        creditResult.stateOfType[RollingBackDebit].authorizedAmount shouldBe entryAmount
        creditResult.stateOfType[RollingBackDebit].amountCaptured shouldBe None
        creditResult.stateOfType[RollingBackDebit].creditReversedResultingBalance shouldBe None

        creditResult.stateOfType[RollingBackDebit]
      }

      "transition to Failed after CreditAdjustmentDone (Release)" in {
        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubAccountMessenger expects (accountIdToCredit, fullCredit) once

          stubAccountMessenger expects (accountIdToDebit, fullRelease) once

          stubResultMessenger expects EntryFailed(txnId, LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE) once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(fullRelease.hashCode(), accountIdToDebit, expectedDebitResultingBalance, theTime)
        )
        creditResult.events shouldBe Seq(CreditAdjustmentDone(expectedDebitResultingBalance))
        creditResult.stateOfType[Failed].code shouldBe LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString
        creditResult.stateOfType[Failed].entryCode shouldBe entryCode
        creditResult.stateOfType[Failed].entryId shouldBe txnId

      }

      "remain in RollingBackDebit on CreditAdjustmentFailed then resume to Failed" in {
        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubAccountMessenger expects (accountIdToCredit, fullCredit) once

          stubAccountMessenger expects (accountIdToDebit, fullRelease) once

          stubAccountMessenger expects (accountIdToDebit, fullRelease) once

          stubResultMessenger expects EntryFailed(txnId, LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE) once
        }

        val givenState = given()

        val creditResult = eventSourcedTestKit.runCommand(
          RejectAccounting(fullRelease.hashCode(), accountIdToDebit, LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE)
        )
        creditResult.events shouldBe Seq(
          CreditAdjustmentFailed(LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString)
        )
        creditResult.stateOfType[ResumableRollingBackDebit].actualState shouldBe givenState

        val resumeResult = eventSourcedTestKit.runCommand(Resume(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        resumeResult.events shouldBe Seq(Resumed())
        resumeResult.stateOfType[RollingBackDebit].code shouldBe Some(
          LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString
        )
        resumeResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        resumeResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        resumeResult.stateOfType[RollingBackDebit].entryCode shouldBe entryCode
        resumeResult.stateOfType[RollingBackDebit].entryId shouldBe txnId
        resumeResult.stateOfType[RollingBackDebit].authorizedAmount shouldBe entryAmount
        resumeResult.stateOfType[RollingBackDebit].amountCaptured shouldBe None
        resumeResult.stateOfType[RollingBackDebit].creditReversedResultingBalance shouldBe None

        val rollbackResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(fullRelease.hashCode(), accountIdToDebit, expectedDebitResultingBalance, theTime)
        )
        rollbackResult.events shouldBe Seq(CreditAdjustmentDone(expectedDebitResultingBalance))
        rollbackResult.stateOfType[Failed].code shouldBe LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString

        rollbackResult.stateOfType[Failed].entryCode shouldBe entryCode
        rollbackResult.stateOfType[Failed].entryId shouldBe txnId

      }
    }

    "Posting" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))
      val expectedCreditResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4), BigDecimal(5))
      val holdTime = DateUtils.now().minus(Duration.ofDays(1))
      val fullPastPost = Post(txnId, entryCode, entryAmount, BigDecimal(0), holdTime)

      def given(): EntryState = {
        val beginResult = eventSourcedTestKit.runCommand(
          Begin(entryCode, accountIdToDebit, accountIdToCredit, entryAmount, ackProbe.ref)
        )
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        beginResult.events shouldBe Seq(
          Started(entryCode, accountIdToDebit, accountIdToCredit, entryAmount, authOnly = false)
        )
        beginResult.stateOfType[Authorizing].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Authorizing].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Authorizing].entryCode shouldBe entryCode
        beginResult.stateOfType[Authorizing].entryId shouldBe txnId
        beginResult.stateOfType[Authorizing].reversalPending shouldBe false
        beginResult.stateOfType[Authorizing].amountAuthorized shouldBe entryAmount

        val debitResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(debitHold.hashCode(), accountIdToDebit, expectedDebitResultingBalance, holdTime)
        )
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, holdTime))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].entryCode shouldBe entryCode
        debitResult.stateOfType[Crediting].entryId shouldBe txnId
        debitResult.stateOfType[Crediting].reversalPending shouldBe false
        debitResult.stateOfType[Crediting].amountAuthorized shouldBe entryAmount
        debitResult.stateOfType[Crediting].captureAmount shouldBe entryAmount
        debitResult.stateOfType[Crediting].debitHoldTimestamp shouldBe holdTime

        val creditResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(fullCredit.hashCode(), accountIdToCredit, expectedCreditResultingBalance, theTime)
        )
        creditResult.events shouldBe Seq(CreditSucceeded(expectedCreditResultingBalance))
        creditResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        creditResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posting].entryCode shouldBe entryCode
        creditResult.stateOfType[Posting].entryId shouldBe txnId
        creditResult.stateOfType[Posting].reversalPending shouldBe false
        creditResult.stateOfType[Posting].amountAuthorized shouldBe entryAmount
        creditResult.stateOfType[Posting].captureAmount shouldBe entryAmount
        creditResult.stateOfType[Posting].debitHoldTimestamp shouldBe holdTime

        creditResult.stateOfType[Posting]
      }

      "transition to Posted after DebitPostSucceeded" in {
        val debitPostedAccountResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubAccountMessenger expects (accountIdToCredit, fullCredit) once

          stubAccountMessenger expects (accountIdToDebit, fullPastPost) once

          stubResultMessenger expects EntrySuccessful(
            txnId,
            debitPostedAccountResultingBalance,
            expectedCreditResultingBalance
          ) once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(fullPastPost.hashCode(), accountIdToDebit, debitPostedAccountResultingBalance, theTime)
        )
        creditResult.events shouldBe Seq(DebitPostSucceeded(debitPostedAccountResultingBalance))
        creditResult.stateOfType[Posted].debitedAccountResultingBalance shouldBe debitPostedAccountResultingBalance
        creditResult.stateOfType[Posted].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posted].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posted].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posted].entryCode shouldBe entryCode
        creditResult.stateOfType[Posted].entryId shouldBe txnId
        creditResult.stateOfType[Posted].reversalPending shouldBe false
        creditResult.stateOfType[Posted].amountCaptured shouldBe entryAmount
      }

      "remain in Posting after DebitPostFailed and resume to Posted" in {
        val debitPostedAccountResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubAccountMessenger expects (accountIdToCredit, fullCredit) once

          stubAccountMessenger expects (accountIdToDebit, fullPastPost) once //"twice" doesn't work, strangely

          stubAccountMessenger expects (accountIdToDebit, fullPastPost) once

          stubResultMessenger expects EntrySuccessful(
            txnId,
            debitPostedAccountResultingBalance,
            expectedCreditResultingBalance
          ) once
        }

        val givenState = given()

        val creditResult = eventSourcedTestKit.runCommand(
          RejectAccounting(fullPastPost.hashCode(), accountIdToDebit, LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE)
        )
        creditResult.events shouldBe Seq(DebitPostFailed(LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString))
        creditResult.stateOfType[ResumablePosting].actualState shouldBe givenState

        val resumeResult = eventSourcedTestKit.runCommand(Resume(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        resumeResult.events shouldBe Seq(Resumed())
        resumeResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        resumeResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        resumeResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        resumeResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        resumeResult.stateOfType[Posting].entryCode shouldBe entryCode
        resumeResult.stateOfType[Posting].entryId shouldBe txnId
        resumeResult.stateOfType[Posting].reversalPending shouldBe false
        resumeResult.stateOfType[Posting].amountAuthorized shouldBe entryAmount
        resumeResult.stateOfType[Posting].captureAmount shouldBe entryAmount
        resumeResult.stateOfType[Posting].debitHoldTimestamp shouldBe holdTime

        val postingResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(fullPastPost.hashCode(), accountIdToDebit, debitPostedAccountResultingBalance, theTime)
        )
        postingResult.events shouldBe Seq(DebitPostSucceeded(debitPostedAccountResultingBalance))
        postingResult.stateOfType[Posted].debitedAccountResultingBalance shouldBe debitPostedAccountResultingBalance
        postingResult.stateOfType[Posted].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        postingResult.stateOfType[Posted].accountToDebit shouldBe accountIdToDebit
        postingResult.stateOfType[Posted].accountToCredit shouldBe accountIdToCredit
        postingResult.stateOfType[Posted].entryCode shouldBe entryCode
        postingResult.stateOfType[Posted].entryId shouldBe txnId
        postingResult.stateOfType[Posted].reversalPending shouldBe false
        postingResult.stateOfType[Posted].amountCaptured shouldBe entryAmount
      }

      "transition to Posted then RollingBackCredit immediately after DebitPostSucceeded (reversal marked)" in {
        val debitPostedAccountResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubAccountMessenger expects (accountIdToCredit, fullCredit) once

          stubAccountMessenger expects (accountIdToDebit, fullPastPost) once

          stubResultMessenger expects EntrySuccessful(
            txnId,
            debitPostedAccountResultingBalance,
            expectedCreditResultingBalance
          ) once

          stubAccountMessenger expects (accountIdToCredit, debitAdjust) once
        }

        given()

        val reverseResult = eventSourcedTestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        reverseResult.events shouldBe Seq(ReversalRequested())
        reverseResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        reverseResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        reverseResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        reverseResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        reverseResult.stateOfType[Posting].entryCode shouldBe entryCode
        reverseResult.stateOfType[Posting].entryId shouldBe txnId
        reverseResult.stateOfType[Posting].reversalPending shouldBe true
        reverseResult.stateOfType[Posting].amountAuthorized shouldBe entryAmount
        reverseResult.stateOfType[Posting].captureAmount shouldBe entryAmount
        reverseResult.stateOfType[Posting].debitHoldTimestamp shouldBe holdTime

        val creditResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(fullPastPost.hashCode(), accountIdToDebit, debitPostedAccountResultingBalance, theTime)
        )
        creditResult.event shouldBe DebitPostSucceeded(debitPostedAccountResultingBalance)
        creditResult.stateOfType[Posted].debitedAccountResultingBalance shouldBe debitPostedAccountResultingBalance
        creditResult.stateOfType[Posted].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posted].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posted].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posted].entryCode shouldBe entryCode
        creditResult.stateOfType[Posted].entryId shouldBe txnId
        creditResult.stateOfType[Posted].reversalPending shouldBe true
        creditResult.stateOfType[Posted].amountCaptured shouldBe entryAmount

        val getResult = eventSourcedTestKit.runCommand(Get)
        getResult.hasNoEvents shouldBe true
        getResult.stateOfType[RollingBackCredit].accountToDebit shouldBe accountIdToDebit
        getResult.stateOfType[RollingBackCredit].accountToCredit shouldBe accountIdToCredit
        getResult.stateOfType[RollingBackCredit].entryCode shouldBe entryCode
        getResult.stateOfType[RollingBackCredit].entryId shouldBe txnId
        getResult.stateOfType[RollingBackCredit].creditedAmount shouldBe entryAmount
        getResult.stateOfType[RollingBackCredit].amountCaptured shouldBe Some(entryAmount)
        getResult.stateOfType[RollingBackCredit].code shouldBe None
      }

      "remain in Posting after DebitPostFailed and resume to Posted then RollingBackCredit immediately (reversal marked)" in {
        val debitPostedAccountResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubAccountMessenger expects (accountIdToCredit, fullCredit) once

          stubAccountMessenger expects (accountIdToDebit, fullPastPost) once //This one is the first attempt that will be rejected

          stubResultMessenger expects CommandRejected(
            txnId,
            LedgerError.UNSUPPORTED_ENTRY_OPERATION_ON_CURRENT_STATE
          ) once

          stubAccountMessenger expects (accountIdToDebit, fullPastPost) once //This one is the third attempt after rejection. Premature resume must be ignored

          stubResultMessenger expects EntrySuccessful(
            txnId,
            debitPostedAccountResultingBalance,
            expectedCreditResultingBalance
          ) once

          stubAccountMessenger expects (accountIdToCredit, debitAdjust) once
        }

        val givenState = given()

        val prematureResumeResult = eventSourcedTestKit.runCommand(Resume(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Nack
        prematureResumeResult.hasNoEvents shouldBe true
        prematureResumeResult.stateOfType[Posting] shouldBe givenState

        val creditResult = eventSourcedTestKit.runCommand(
          RejectAccounting(fullPastPost.hashCode(), accountIdToDebit, LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE)
        )
        creditResult.events shouldBe Seq(DebitPostFailed(LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString))
        creditResult.stateOfType[ResumablePosting].actualState shouldBe givenState

        val reverseResult = eventSourcedTestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        reverseResult.events shouldBe Seq(ReversalRequested())
        reverseResult
          .stateOfType[ResumablePosting]
          .actualState shouldBe givenState.asInstanceOf[Posting].copy(reversalPending = true)

        val resumeResult = eventSourcedTestKit.runCommand(Resume(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        resumeResult.events shouldBe Seq(Resumed())
        resumeResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        resumeResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        resumeResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        resumeResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        resumeResult.stateOfType[Posting].entryCode shouldBe entryCode
        resumeResult.stateOfType[Posting].entryId shouldBe txnId
        resumeResult.stateOfType[Posting].reversalPending shouldBe true
        resumeResult.stateOfType[Posting].amountAuthorized shouldBe entryAmount
        resumeResult.stateOfType[Posting].captureAmount shouldBe entryAmount
        resumeResult.stateOfType[Posting].debitHoldTimestamp shouldBe holdTime

        val postingResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(fullPastPost.hashCode(), accountIdToDebit, debitPostedAccountResultingBalance, theTime)
        )
        postingResult.event shouldBe DebitPostSucceeded(debitPostedAccountResultingBalance)
        postingResult.stateOfType[Posted].debitedAccountResultingBalance shouldBe debitPostedAccountResultingBalance
        postingResult.stateOfType[Posted].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        postingResult.stateOfType[Posted].accountToDebit shouldBe accountIdToDebit
        postingResult.stateOfType[Posted].accountToCredit shouldBe accountIdToCredit
        postingResult.stateOfType[Posted].entryCode shouldBe entryCode
        postingResult.stateOfType[Posted].entryId shouldBe txnId
        postingResult.stateOfType[Posted].reversalPending shouldBe true
        postingResult.stateOfType[Posted].amountCaptured shouldBe entryAmount

        val getResult = eventSourcedTestKit.runCommand(Get)
        getResult.hasNoEvents shouldBe true
        getResult.stateOfType[RollingBackCredit].accountToDebit shouldBe accountIdToDebit
        getResult.stateOfType[RollingBackCredit].accountToCredit shouldBe accountIdToCredit
        getResult.stateOfType[RollingBackCredit].entryCode shouldBe entryCode
        getResult.stateOfType[RollingBackCredit].entryId shouldBe txnId
        getResult.stateOfType[RollingBackCredit].creditedAmount shouldBe entryAmount
        getResult.stateOfType[RollingBackCredit].amountCaptured shouldBe Some(entryAmount)
        getResult.stateOfType[RollingBackCredit].code shouldBe None
      }
    }

    "Posted" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))
      val expectedCreditResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4), BigDecimal(5))
      val expectedDebitPostedBalance: ResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

      def given(): EntryState = {
        val beginResult = eventSourcedTestKit.runCommand(
          Begin(entryCode, accountIdToDebit, accountIdToCredit, entryAmount, ackProbe.ref)
        )
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        beginResult.events shouldBe Seq(
          Started(entryCode, accountIdToDebit, accountIdToCredit, entryAmount, authOnly = false)
        )
        beginResult.stateOfType[Authorizing].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Authorizing].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Authorizing].entryCode shouldBe entryCode
        beginResult.stateOfType[Authorizing].entryId shouldBe txnId
        beginResult.stateOfType[Authorizing].reversalPending shouldBe false
        beginResult.stateOfType[Authorizing].amountAuthorized shouldBe entryAmount

        val debitResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(debitHold.hashCode(), accountIdToDebit, expectedDebitResultingBalance, theTime)
        )
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, theTime))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].entryCode shouldBe entryCode
        debitResult.stateOfType[Crediting].entryId shouldBe txnId
        debitResult.stateOfType[Crediting].reversalPending shouldBe false
        debitResult.stateOfType[Crediting].amountAuthorized shouldBe entryAmount
        debitResult.stateOfType[Crediting].captureAmount shouldBe entryAmount
        debitResult.stateOfType[Crediting].debitHoldTimestamp shouldBe theTime

        val creditResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(fullCredit.hashCode(), accountIdToCredit, expectedCreditResultingBalance, theTime)
        )
        creditResult.events shouldBe Seq(CreditSucceeded(expectedCreditResultingBalance))
        creditResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        creditResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posting].entryCode shouldBe entryCode
        creditResult.stateOfType[Posting].entryId shouldBe txnId
        creditResult.stateOfType[Posting].reversalPending shouldBe false
        creditResult.stateOfType[Posting].amountAuthorized shouldBe entryAmount
        creditResult.stateOfType[Posting].captureAmount shouldBe entryAmount
        creditResult.stateOfType[Posting].debitHoldTimestamp shouldBe theTime

        val postingResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(fullPost.hashCode(), accountIdToDebit, expectedDebitPostedBalance, theTime)
        )
        postingResult.events shouldBe Seq(DebitPostSucceeded(expectedDebitPostedBalance))
        postingResult.stateOfType[Posted].debitedAccountResultingBalance shouldBe expectedDebitPostedBalance
        postingResult.stateOfType[Posted].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        postingResult.stateOfType[Posted].accountToDebit shouldBe accountIdToDebit
        postingResult.stateOfType[Posted].accountToCredit shouldBe accountIdToCredit
        postingResult.stateOfType[Posted].entryCode shouldBe entryCode
        postingResult.stateOfType[Posted].entryId shouldBe txnId
        postingResult.stateOfType[Posted].reversalPending shouldBe false
        postingResult.stateOfType[Posted].amountCaptured shouldBe entryAmount

        postingResult.stateOfType[Posted]
      }

      "transition to RollingBackCredit on ReversalRequested" in {
        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubAccountMessenger expects (accountIdToCredit, fullCredit) once

          stubAccountMessenger expects (accountIdToDebit, fullPost) once

          stubResultMessenger expects EntrySuccessful(
            txnId,
            expectedDebitPostedBalance,
            expectedCreditResultingBalance
          ) once

          stubAccountMessenger expects (accountIdToCredit, debitAdjust) once
        }

        given()

        val reverseResult = eventSourcedTestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        reverseResult.events shouldBe Seq(ReversalRequested())
        reverseResult.stateOfType[RollingBackCredit].accountToDebit shouldBe accountIdToDebit
        reverseResult.stateOfType[RollingBackCredit].accountToCredit shouldBe accountIdToCredit
        reverseResult.stateOfType[RollingBackCredit].entryCode shouldBe entryCode
        reverseResult.stateOfType[RollingBackCredit].entryId shouldBe txnId
        reverseResult.stateOfType[RollingBackCredit].creditedAmount shouldBe entryAmount
        reverseResult.stateOfType[RollingBackCredit].amountCaptured shouldBe Some(entryAmount)
        reverseResult.stateOfType[RollingBackCredit].code shouldBe None
      }
    }

    "RollingBackCredit" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))
      val expectedCreditResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4), BigDecimal(5))
      val expectedCreditReversedResultingBalance: ResultingBalance =
        ResultingBalance(BigDecimal(4.75), BigDecimal(5.75))
      val expectedDebitPostedBalance: ResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

      def given(): EntryState = {
        val beginResult = eventSourcedTestKit.runCommand(
          Begin(entryCode, accountIdToDebit, accountIdToCredit, entryAmount, ackProbe.ref)
        )
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        beginResult.events shouldBe Seq(
          Started(entryCode, accountIdToDebit, accountIdToCredit, entryAmount, authOnly = false)
        )
        beginResult.stateOfType[Authorizing].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Authorizing].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Authorizing].entryCode shouldBe entryCode
        beginResult.stateOfType[Authorizing].entryId shouldBe txnId
        beginResult.stateOfType[Authorizing].reversalPending shouldBe false
        beginResult.stateOfType[Authorizing].amountAuthorized shouldBe entryAmount

        val debitResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(debitHold.hashCode(), accountIdToDebit, expectedDebitResultingBalance, theTime)
        )
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, theTime))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].entryCode shouldBe entryCode
        debitResult.stateOfType[Crediting].entryId shouldBe txnId
        debitResult.stateOfType[Crediting].reversalPending shouldBe false
        debitResult.stateOfType[Crediting].amountAuthorized shouldBe entryAmount
        debitResult.stateOfType[Crediting].captureAmount shouldBe entryAmount
        debitResult.stateOfType[Crediting].debitHoldTimestamp shouldBe theTime

        val creditResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(fullCredit.hashCode(), accountIdToCredit, expectedCreditResultingBalance, theTime)
        )
        creditResult.events shouldBe Seq(CreditSucceeded(expectedCreditResultingBalance))
        creditResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        creditResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posting].entryCode shouldBe entryCode
        creditResult.stateOfType[Posting].entryId shouldBe txnId
        creditResult.stateOfType[Posting].reversalPending shouldBe false
        creditResult.stateOfType[Posting].amountAuthorized shouldBe entryAmount
        creditResult.stateOfType[Posting].captureAmount shouldBe entryAmount
        creditResult.stateOfType[Posting].debitHoldTimestamp shouldBe theTime

        val postingResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(fullPost.hashCode(), accountIdToDebit, expectedDebitPostedBalance, theTime)
        )
        postingResult.events shouldBe Seq(DebitPostSucceeded(expectedDebitPostedBalance))
        postingResult.stateOfType[Posted].debitedAccountResultingBalance shouldBe expectedDebitPostedBalance
        postingResult.stateOfType[Posted].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        postingResult.stateOfType[Posted].accountToDebit shouldBe accountIdToDebit
        postingResult.stateOfType[Posted].accountToCredit shouldBe accountIdToCredit
        postingResult.stateOfType[Posted].entryCode shouldBe entryCode
        postingResult.stateOfType[Posted].entryId shouldBe txnId
        postingResult.stateOfType[Posted].reversalPending shouldBe false
        postingResult.stateOfType[Posted].amountCaptured shouldBe entryAmount

        val reverseResult = eventSourcedTestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        reverseResult.events shouldBe Seq(ReversalRequested())
        reverseResult.stateOfType[RollingBackCredit].accountToDebit shouldBe accountIdToDebit
        reverseResult.stateOfType[RollingBackCredit].accountToCredit shouldBe accountIdToCredit
        reverseResult.stateOfType[RollingBackCredit].entryCode shouldBe entryCode
        reverseResult.stateOfType[RollingBackCredit].entryId shouldBe txnId
        reverseResult.stateOfType[RollingBackCredit].creditedAmount shouldBe entryAmount
        reverseResult.stateOfType[RollingBackCredit].amountCaptured shouldBe Some(entryAmount)
        reverseResult.stateOfType[RollingBackCredit].code shouldBe None

        reverseResult.stateOfType[RollingBackCredit]
      }

      "transition to RollingBackDebit on DebitAdjustmentDone" in {
        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubAccountMessenger expects (accountIdToCredit, fullCredit) once

          stubAccountMessenger expects (accountIdToDebit, fullPost) once

          stubResultMessenger expects EntrySuccessful(
            txnId,
            expectedDebitPostedBalance,
            expectedCreditResultingBalance
          ) once

          stubAccountMessenger expects (accountIdToCredit, debitAdjust) once

          stubAccountMessenger expects (accountIdToDebit, creditAdjust) once
        }

        given()

        val reverseResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(debitAdjust.hashCode(), accountIdToCredit, expectedCreditReversedResultingBalance, theTime)
        )
        reverseResult.events shouldBe Seq(DebitAdjustmentDone(expectedCreditReversedResultingBalance))
        reverseResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        reverseResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        reverseResult.stateOfType[RollingBackDebit].entryCode shouldBe entryCode
        reverseResult.stateOfType[RollingBackDebit].entryId shouldBe txnId
        reverseResult.stateOfType[RollingBackDebit].authorizedAmount shouldBe entryAmount
        reverseResult.stateOfType[RollingBackDebit].amountCaptured shouldBe Some(entryAmount)
        reverseResult.stateOfType[RollingBackDebit].creditReversedResultingBalance shouldBe Some(
          expectedCreditReversedResultingBalance
        )
      }

      "remain in RollingBackCredit on DebitAdjustmentFailed and resume to RollingBackDebit" in {
        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubAccountMessenger expects (accountIdToCredit, fullCredit) once

          stubAccountMessenger expects (accountIdToDebit, fullPost) once

          stubResultMessenger expects EntrySuccessful(
            txnId,
            expectedDebitPostedBalance,
            expectedCreditResultingBalance
          ) once

          stubAccountMessenger expects (accountIdToCredit, debitAdjust) once

          stubAccountMessenger expects (accountIdToCredit, debitAdjust) once

          stubAccountMessenger expects (accountIdToDebit, creditAdjust) once
        }

        val givenState = given()

        val reverseResult = eventSourcedTestKit.runCommand(
          RejectAccounting(debitAdjust.hashCode(), accountIdToCredit, LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE)
        )
        reverseResult.events shouldBe Seq(
          DebitAdjustmentFailed(LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString)
        )
        reverseResult.stateOfType[ResumableRollingBackCredit].actualState shouldBe givenState

        val resumeResult = eventSourcedTestKit.runCommand(Resume(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        resumeResult.events shouldBe Seq(Resumed())
        resumeResult.stateOfType[RollingBackCredit].accountToDebit shouldBe accountIdToDebit
        resumeResult.stateOfType[RollingBackCredit].accountToCredit shouldBe accountIdToCredit
        resumeResult.stateOfType[RollingBackCredit].entryCode shouldBe entryCode
        resumeResult.stateOfType[RollingBackCredit].entryId shouldBe txnId
        resumeResult.stateOfType[RollingBackCredit].creditedAmount shouldBe entryAmount
        resumeResult.stateOfType[RollingBackCredit].amountCaptured shouldBe Some(entryAmount)
        resumeResult.stateOfType[RollingBackCredit].code shouldBe None

        val rollbackResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(debitAdjust.hashCode(), accountIdToCredit, expectedCreditReversedResultingBalance, theTime)
        )
        rollbackResult.events shouldBe Seq(DebitAdjustmentDone(expectedCreditReversedResultingBalance))
        rollbackResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        rollbackResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        rollbackResult.stateOfType[RollingBackDebit].entryCode shouldBe entryCode
        rollbackResult.stateOfType[RollingBackDebit].entryId shouldBe txnId
        rollbackResult.stateOfType[RollingBackDebit].authorizedAmount shouldBe entryAmount
        rollbackResult.stateOfType[RollingBackDebit].amountCaptured shouldBe Some(entryAmount)
        rollbackResult.stateOfType[RollingBackDebit].creditReversedResultingBalance shouldBe Some(
          expectedCreditReversedResultingBalance
        )

      }
    }

    "RollingBackDebit due to Reversal" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))
      val expectedCreditResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4), BigDecimal(5))
      val expectedCreditReversedResultingBalance: ResultingBalance =
        ResultingBalance(BigDecimal(4.75), BigDecimal(5.75))
      val expectedDebitReversedResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4.25), BigDecimal(5.25))
      val expectedDebitPostedBalance: ResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

      def given(): EntryState = {
        val beginResult = eventSourcedTestKit.runCommand(
          Begin(entryCode, accountIdToDebit, accountIdToCredit, entryAmount, ackProbe.ref)
        )
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        beginResult.events shouldBe Seq(
          Started(entryCode, accountIdToDebit, accountIdToCredit, entryAmount, authOnly = false)
        )
        beginResult.stateOfType[Authorizing].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Authorizing].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Authorizing].entryCode shouldBe entryCode
        beginResult.stateOfType[Authorizing].entryId shouldBe txnId
        beginResult.stateOfType[Authorizing].reversalPending shouldBe false
        beginResult.stateOfType[Authorizing].amountAuthorized shouldBe entryAmount

        val debitResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(debitHold.hashCode(), accountIdToDebit, expectedDebitResultingBalance, theTime)
        )
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, theTime))
        debitResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Crediting].entryCode shouldBe entryCode
        debitResult.stateOfType[Crediting].entryId shouldBe txnId
        debitResult.stateOfType[Crediting].reversalPending shouldBe false
        debitResult.stateOfType[Crediting].amountAuthorized shouldBe entryAmount
        debitResult.stateOfType[Crediting].captureAmount shouldBe entryAmount
        debitResult.stateOfType[Crediting].debitHoldTimestamp shouldBe theTime

        val creditResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(fullCredit.hashCode(), accountIdToCredit, expectedCreditResultingBalance, theTime)
        )
        creditResult.events shouldBe Seq(CreditSucceeded(expectedCreditResultingBalance))
        creditResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        creditResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posting].entryCode shouldBe entryCode
        creditResult.stateOfType[Posting].entryId shouldBe txnId
        creditResult.stateOfType[Posting].reversalPending shouldBe false
        creditResult.stateOfType[Posting].amountAuthorized shouldBe entryAmount
        creditResult.stateOfType[Posting].captureAmount shouldBe entryAmount
        creditResult.stateOfType[Posting].debitHoldTimestamp shouldBe theTime

        val postingResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(fullPost.hashCode(), accountIdToDebit, expectedDebitPostedBalance, theTime)
        )
        postingResult.events shouldBe Seq(DebitPostSucceeded(expectedDebitPostedBalance))
        postingResult.stateOfType[Posted].debitedAccountResultingBalance shouldBe expectedDebitPostedBalance
        postingResult.stateOfType[Posted].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        postingResult.stateOfType[Posted].accountToDebit shouldBe accountIdToDebit
        postingResult.stateOfType[Posted].accountToCredit shouldBe accountIdToCredit
        postingResult.stateOfType[Posted].entryCode shouldBe entryCode
        postingResult.stateOfType[Posted].entryId shouldBe txnId
        postingResult.stateOfType[Posted].reversalPending shouldBe false
        postingResult.stateOfType[Posted].amountCaptured shouldBe entryAmount

        val reverseResult = eventSourcedTestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        reverseResult.events shouldBe Seq(ReversalRequested())
        reverseResult.stateOfType[RollingBackCredit].accountToDebit shouldBe accountIdToDebit
        reverseResult.stateOfType[RollingBackCredit].accountToCredit shouldBe accountIdToCredit
        reverseResult.stateOfType[RollingBackCredit].entryCode shouldBe entryCode
        reverseResult.stateOfType[RollingBackCredit].entryId shouldBe txnId
        reverseResult.stateOfType[RollingBackCredit].creditedAmount shouldBe entryAmount
        reverseResult.stateOfType[RollingBackCredit].amountCaptured shouldBe Some(entryAmount)
        reverseResult.stateOfType[RollingBackCredit].code shouldBe None

        val reverseCreditResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(debitAdjust.hashCode(), accountIdToCredit, expectedCreditReversedResultingBalance, theTime)
        )
        reverseCreditResult.events shouldBe Seq(DebitAdjustmentDone(expectedCreditReversedResultingBalance))
        reverseCreditResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        reverseCreditResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        reverseCreditResult.stateOfType[RollingBackDebit].entryCode shouldBe entryCode
        reverseCreditResult.stateOfType[RollingBackDebit].entryId shouldBe txnId
        reverseCreditResult.stateOfType[RollingBackDebit].authorizedAmount shouldBe entryAmount
        reverseCreditResult.stateOfType[RollingBackDebit].amountCaptured shouldBe Some(entryAmount)
        reverseCreditResult.stateOfType[RollingBackDebit].creditReversedResultingBalance shouldBe Some(
          expectedCreditReversedResultingBalance
        )

        reverseCreditResult.stateOfType[RollingBackDebit]
      }

      "transition to Reversed after CreditAdjustmentDone" in {
        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubAccountMessenger expects (accountIdToCredit, fullCredit) once

          stubAccountMessenger expects (accountIdToDebit, fullPost) once

          stubResultMessenger expects EntrySuccessful(
            txnId,
            expectedDebitPostedBalance,
            expectedCreditResultingBalance
          ) once

          stubAccountMessenger expects (accountIdToCredit, debitAdjust) once

          stubAccountMessenger expects (accountIdToDebit, creditAdjust) once

          stubResultMessenger expects EntryReversed(
            txnId,
            expectedDebitReversedResultingBalance,
            Some(expectedCreditReversedResultingBalance)
          ) once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(creditAdjust.hashCode(), accountIdToDebit, expectedDebitReversedResultingBalance, theTime)
        )
        creditResult.events shouldBe Seq(CreditAdjustmentDone(expectedDebitReversedResultingBalance))
        creditResult.stateOfType[Reversed].entryCode shouldBe entryCode
        creditResult.stateOfType[Reversed].entryId shouldBe txnId
        creditResult.stateOfType[Reversed].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Reversed].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Reversed].creditReversedResultingBalance shouldBe Some(
          expectedCreditReversedResultingBalance
        )
        creditResult.stateOfType[Reversed].debitReversedResultingBalance shouldBe expectedDebitReversedResultingBalance
      }

      "remain in RollingBackDebit on CreditAdjustmentFailed then resumed to Reversed" in {
        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubAccountMessenger expects (accountIdToCredit, fullCredit) once

          stubAccountMessenger expects (accountIdToDebit, fullPost) once

          stubResultMessenger expects EntrySuccessful(
            txnId,
            expectedDebitPostedBalance,
            expectedCreditResultingBalance
          ) once

          stubAccountMessenger expects (accountIdToCredit, debitAdjust) once

          stubAccountMessenger expects (accountIdToDebit, creditAdjust) once

          stubAccountMessenger expects (accountIdToDebit, creditAdjust) once

          stubResultMessenger expects EntryReversed(
            txnId,
            expectedDebitReversedResultingBalance,
            Some(expectedCreditReversedResultingBalance)
          ) once
        }

        val givenState = given()

        val creditResult = eventSourcedTestKit.runCommand(
          RejectAccounting(creditAdjust.hashCode(), accountIdToDebit, LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE)
        )
        creditResult.events shouldBe Seq(
          CreditAdjustmentFailed(LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString)
        )
        creditResult.stateOfType[ResumableRollingBackDebit].actualState shouldBe givenState

        val resumeResult = eventSourcedTestKit.runCommand(Resume(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        resumeResult.events shouldBe Seq(Resumed())
        resumeResult.stateOfType[RollingBackDebit].code shouldBe None
        resumeResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        resumeResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        resumeResult.stateOfType[RollingBackDebit].entryCode shouldBe entryCode
        resumeResult.stateOfType[RollingBackDebit].entryId shouldBe txnId
        resumeResult.stateOfType[RollingBackDebit].authorizedAmount shouldBe entryAmount
        resumeResult.stateOfType[RollingBackDebit].amountCaptured shouldBe Some(entryAmount)
        resumeResult.stateOfType[RollingBackDebit].creditReversedResultingBalance shouldBe Some(
          expectedCreditReversedResultingBalance
        )

        val rollbackResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(creditAdjust.hashCode(), accountIdToDebit, expectedDebitReversedResultingBalance, theTime)
        )
        rollbackResult.events shouldBe Seq(CreditAdjustmentDone(expectedDebitReversedResultingBalance))
        rollbackResult.stateOfType[Reversed].entryCode shouldBe entryCode
        rollbackResult.stateOfType[Reversed].entryId shouldBe txnId
        rollbackResult.stateOfType[Reversed].accountToDebit shouldBe accountIdToDebit
        rollbackResult.stateOfType[Reversed].accountToCredit shouldBe accountIdToCredit
        rollbackResult.stateOfType[Reversed].creditReversedResultingBalance shouldBe Some(
          expectedCreditReversedResultingBalance
        )
        rollbackResult
          .stateOfType[Reversed]
          .debitReversedResultingBalance shouldBe expectedDebitReversedResultingBalance
      }
    }

    "Authorizing (auth only)" must {
      def given(): EntryState = {
        val beginResult = eventSourcedTestKit.runCommand(
          Begin(entryCode, accountIdToDebit, accountIdToCredit, entryAmount, ackProbe.ref, authOnly = true)
        )
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        beginResult.events shouldBe Seq(
          Started(entryCode, accountIdToDebit, accountIdToCredit, entryAmount, authOnly = true)
        )
        beginResult.stateOfType[Authorizing].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Authorizing].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Authorizing].entryCode shouldBe entryCode
        beginResult.stateOfType[Authorizing].entryId shouldBe txnId
        beginResult.stateOfType[Authorizing].reversalPending shouldBe false
        beginResult.stateOfType[Authorizing].amountAuthorized shouldBe entryAmount

        beginResult.stateOfType[Authorizing]
      }

      "transition to Pending after DebitHoldSucceeded" in {
        val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))

        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubResultMessenger expects EntryPending(txnId, expectedDebitResultingBalance) once
        }

        given()

        val debitResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(debitHold.hashCode(), accountIdToDebit, expectedDebitResultingBalance, theTime)
        )
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, theTime))
        debitResult.stateOfType[Pending].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Pending].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Pending].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Pending].entryCode shouldBe entryCode
        debitResult.stateOfType[Pending].entryId shouldBe txnId
        debitResult.stateOfType[Pending].reversalPending shouldBe false
        debitResult.stateOfType[Pending].amountAuthorized shouldBe entryAmount
        debitResult.stateOfType[Pending].debitHoldTimestamp shouldBe theTime

      }

      "transition to Pending then immediately to RollingBackDebit after DebitHoldSucceeded (reversal marked)" in {
        val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))

        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubResultMessenger expects EntryPending(txnId, expectedDebitResultingBalance) once

          stubAccountMessenger expects (accountIdToDebit, fullRelease) once
        }

        given()

        val reverseResult = eventSourcedTestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        reverseResult.events shouldBe Seq(ReversalRequested())
        reverseResult.stateOfType[Authorizing].accountToDebit shouldBe accountIdToDebit
        reverseResult.stateOfType[Authorizing].accountToCredit shouldBe accountIdToCredit
        reverseResult.stateOfType[Authorizing].entryCode shouldBe entryCode
        reverseResult.stateOfType[Authorizing].entryId shouldBe txnId
        reverseResult.stateOfType[Authorizing].reversalPending shouldBe true
        reverseResult.stateOfType[Authorizing].amountAuthorized shouldBe entryAmount

        val debitResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(debitHold.hashCode(), accountIdToDebit, expectedDebitResultingBalance, theTime)
        )
        debitResult.event shouldBe DebitHoldSucceeded(expectedDebitResultingBalance, theTime)
        debitResult.stateOfType[Pending].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Pending].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Pending].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Pending].entryCode shouldBe entryCode
        debitResult.stateOfType[Pending].entryId shouldBe txnId
        debitResult.stateOfType[Pending].reversalPending shouldBe true
        debitResult.stateOfType[Pending].amountAuthorized shouldBe entryAmount
        debitResult.stateOfType[Pending].debitHoldTimestamp shouldBe theTime

        val getResult = eventSourcedTestKit.runCommand(Get)
        getResult.hasNoEvents shouldBe true
        getResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        getResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        getResult.stateOfType[RollingBackDebit].entryCode shouldBe entryCode
        getResult.stateOfType[RollingBackDebit].entryId shouldBe txnId
        getResult.stateOfType[RollingBackDebit].authorizedAmount shouldBe entryAmount
        getResult.stateOfType[RollingBackDebit].amountCaptured shouldBe None
        getResult.stateOfType[RollingBackDebit].code shouldBe None
        getResult.stateOfType[RollingBackDebit].creditReversedResultingBalance shouldBe None
      }

      "transition to Failed after DebitHoldFailed" in {
        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubResultMessenger expects EntryFailed(txnId, LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE) once
        }

        given()

        val debitResult = eventSourcedTestKit.runCommand(
          RejectAccounting(debitHold.hashCode(), accountIdToDebit, LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE)
        )
        debitResult.events shouldBe Seq(DebitHoldFailed(LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString))
        debitResult.stateOfType[Failed].code shouldBe LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString
        debitResult.stateOfType[Failed].entryCode shouldBe entryCode
        debitResult.stateOfType[Failed].entryId shouldBe txnId

      }
    }

    "Pending" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))

      def given(): EntryState = {
        val beginResult = eventSourcedTestKit.runCommand(
          Begin(entryCode, accountIdToDebit, accountIdToCredit, entryAmount, ackProbe.ref, authOnly = true)
        )
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        beginResult.events shouldBe Seq(
          Started(entryCode, accountIdToDebit, accountIdToCredit, entryAmount, authOnly = true)
        )
        beginResult.stateOfType[Authorizing].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Authorizing].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Authorizing].entryCode shouldBe entryCode
        beginResult.stateOfType[Authorizing].entryId shouldBe txnId
        beginResult.stateOfType[Authorizing].reversalPending shouldBe false
        beginResult.stateOfType[Authorizing].amountAuthorized shouldBe entryAmount

        val debitResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(debitHold.hashCode(), accountIdToDebit, expectedDebitResultingBalance, theTime)
        )
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, theTime))
        debitResult.stateOfType[Pending].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Pending].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Pending].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Pending].entryCode shouldBe entryCode
        debitResult.stateOfType[Pending].entryId shouldBe txnId
        debitResult.stateOfType[Pending].reversalPending shouldBe false
        debitResult.stateOfType[Pending].amountAuthorized shouldBe entryAmount
        debitResult.stateOfType[Pending].debitHoldTimestamp shouldBe theTime

        debitResult.stateOfType[Pending]
      }

      "transition to Crediting (partial) after CaptureRequested" in {
        val captureAmount: BigDecimal = BigDecimal(23)
        val partialCredit = Credit(txnId, entryCode, captureAmount)

        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubResultMessenger expects EntryPending(txnId, expectedDebitResultingBalance) once

          stubAccountMessenger expects (accountIdToCredit, partialCredit) once
        }

        given()

        val pendingResult = eventSourcedTestKit.runCommand(Capture(captureAmount, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        pendingResult.events shouldBe Seq(CaptureRequested(captureAmount))
        pendingResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        pendingResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        pendingResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        pendingResult.stateOfType[Crediting].entryCode shouldBe entryCode
        pendingResult.stateOfType[Crediting].entryId shouldBe txnId
        pendingResult.stateOfType[Crediting].reversalPending shouldBe false
        pendingResult.stateOfType[Crediting].amountAuthorized shouldBe entryAmount
        pendingResult.stateOfType[Crediting].captureAmount shouldBe captureAmount
        pendingResult.stateOfType[Crediting].debitHoldTimestamp shouldBe theTime
      }

      "transition to RollingBackDebit after ReverseRequested" in {

        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubResultMessenger expects EntryPending(txnId, expectedDebitResultingBalance) once

          stubAccountMessenger expects (accountIdToDebit, fullRelease) once

        }

        given()

        val debitResult = eventSourcedTestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        debitResult.events shouldBe Seq(ReversalRequested())
        debitResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[RollingBackDebit].entryCode shouldBe entryCode
        debitResult.stateOfType[RollingBackDebit].entryId shouldBe txnId
        debitResult.stateOfType[RollingBackDebit].authorizedAmount shouldBe entryAmount
        debitResult.stateOfType[RollingBackDebit].amountCaptured shouldBe None
        debitResult.stateOfType[RollingBackDebit].code shouldBe None
        debitResult.stateOfType[RollingBackDebit].creditReversedResultingBalance shouldBe None
      }

      "do nothing on over-Capture" in {
        val captureAmount: BigDecimal = BigDecimal(100.00001)

        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubResultMessenger expects EntryPending(txnId, expectedDebitResultingBalance) once

          stubResultMessenger expects CaptureRejected(txnId, LedgerError.CAPTURE_MORE_THAN_AUTHORIZED) once
        }

        given()

        val pendingResult = eventSourcedTestKit.runCommand(Capture(captureAmount, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        pendingResult.hasNoEvents shouldBe true
        // Nothing should change
        pendingResult.stateOfType[Pending].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        pendingResult.stateOfType[Pending].accountToDebit shouldBe accountIdToDebit
        pendingResult.stateOfType[Pending].accountToCredit shouldBe accountIdToCredit
        pendingResult.stateOfType[Pending].entryCode shouldBe entryCode
        pendingResult.stateOfType[Pending].entryId shouldBe txnId
        pendingResult.stateOfType[Pending].reversalPending shouldBe false
        pendingResult.stateOfType[Pending].amountAuthorized shouldBe entryAmount
        pendingResult.stateOfType[Pending].debitHoldTimestamp shouldBe theTime
      }
    }

    "Crediting (partial)" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))
      val holdTime = DateUtils.now().minus(Duration.ofDays(1))
      val captureAmount: BigDecimal = BigDecimal(23)
      val partialCredit = Credit(txnId, entryCode, captureAmount)
      val partialPost = Post(txnId, entryCode, captureAmount, entryAmount - captureAmount, holdTime)

      def given(): EntryState = {
        val beginResult = eventSourcedTestKit.runCommand(
          Begin(entryCode, accountIdToDebit, accountIdToCredit, entryAmount, ackProbe.ref, authOnly = true)
        )
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        beginResult.events shouldBe Seq(
          Started(entryCode, accountIdToDebit, accountIdToCredit, entryAmount, authOnly = true)
        )
        beginResult.stateOfType[Authorizing].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Authorizing].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Authorizing].entryCode shouldBe entryCode
        beginResult.stateOfType[Authorizing].entryId shouldBe txnId
        beginResult.stateOfType[Authorizing].reversalPending shouldBe false
        beginResult.stateOfType[Authorizing].amountAuthorized shouldBe entryAmount

        val debitResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(debitHold.hashCode(), accountIdToDebit, expectedDebitResultingBalance, holdTime)
        )
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, holdTime))
        debitResult.stateOfType[Pending].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Pending].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Pending].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Pending].entryCode shouldBe entryCode
        debitResult.stateOfType[Pending].entryId shouldBe txnId
        debitResult.stateOfType[Pending].reversalPending shouldBe false
        debitResult.stateOfType[Pending].amountAuthorized shouldBe entryAmount
        debitResult.stateOfType[Pending].debitHoldTimestamp shouldBe holdTime

        val pendingResult = eventSourcedTestKit.runCommand(Capture(captureAmount, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        pendingResult.events shouldBe Seq(CaptureRequested(captureAmount))
        pendingResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        pendingResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        pendingResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        pendingResult.stateOfType[Crediting].entryCode shouldBe entryCode
        pendingResult.stateOfType[Crediting].entryId shouldBe txnId
        pendingResult.stateOfType[Crediting].reversalPending shouldBe false
        pendingResult.stateOfType[Crediting].amountAuthorized shouldBe entryAmount
        pendingResult.stateOfType[Crediting].captureAmount shouldBe captureAmount
        pendingResult.stateOfType[Crediting].debitHoldTimestamp shouldBe holdTime

        pendingResult.stateOfType[Crediting]
      }

      "transition to Posting after CreditingSucceeded" in {
        val expectedCreditResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4), BigDecimal(5))
        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubResultMessenger expects EntryPending(txnId, expectedDebitResultingBalance) once

          stubAccountMessenger expects (accountIdToCredit, partialCredit) once

          stubAccountMessenger expects (accountIdToDebit, partialPost) once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(partialCredit.hashCode(), accountIdToCredit, expectedCreditResultingBalance, theTime)
        )
        creditResult.events shouldBe Seq(CreditSucceeded(expectedCreditResultingBalance))
        creditResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        creditResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posting].entryCode shouldBe entryCode
        creditResult.stateOfType[Posting].entryId shouldBe txnId
        creditResult.stateOfType[Posting].reversalPending shouldBe false
        creditResult.stateOfType[Posting].amountAuthorized shouldBe entryAmount
        creditResult.stateOfType[Posting].captureAmount shouldBe captureAmount
        creditResult.stateOfType[Posting].debitHoldTimestamp shouldBe holdTime

      }

      "transition to RollingBackDebit after CreditingFailed" in {
        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubResultMessenger expects EntryPending(txnId, expectedDebitResultingBalance) once

          stubAccountMessenger expects (accountIdToCredit, partialCredit) once

          stubAccountMessenger expects (accountIdToDebit, fullRelease) once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(
          RejectAccounting(
            partialCredit.hashCode(),
            accountIdToCredit,
            LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE
          )
        )
        creditResult.events shouldBe Seq(CreditFailed(LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString))
        creditResult.stateOfType[RollingBackDebit].code shouldBe Some(
          LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString
        )
        creditResult.stateOfType[RollingBackDebit].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[RollingBackDebit].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[RollingBackDebit].entryCode shouldBe entryCode
        creditResult.stateOfType[RollingBackDebit].entryId shouldBe txnId
        creditResult.stateOfType[RollingBackDebit].authorizedAmount shouldBe entryAmount
        creditResult.stateOfType[RollingBackDebit].amountCaptured shouldBe None
      }
    }

    "Posting (partial)" must {
      val expectedDebitResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))
      val expectedCreditResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4), BigDecimal(5))

      val holdTime = DateUtils.now().minus(Duration.ofDays(1))
      val captureAmount: BigDecimal = BigDecimal(23)
      val partialCredit = Credit(txnId, entryCode, captureAmount)
      val partialPost = Post(txnId, entryCode, captureAmount, entryAmount - captureAmount, holdTime)

      def given(): EntryState = {
        val beginResult = eventSourcedTestKit.runCommand(
          Begin(entryCode, accountIdToDebit, accountIdToCredit, entryAmount, ackProbe.ref, authOnly = true)
        )
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        beginResult.events shouldBe Seq(
          Started(entryCode, accountIdToDebit, accountIdToCredit, entryAmount, authOnly = true)
        )
        beginResult.stateOfType[Authorizing].accountToDebit shouldBe accountIdToDebit
        beginResult.stateOfType[Authorizing].accountToCredit shouldBe accountIdToCredit
        beginResult.stateOfType[Authorizing].entryCode shouldBe entryCode
        beginResult.stateOfType[Authorizing].entryId shouldBe txnId
        beginResult.stateOfType[Authorizing].reversalPending shouldBe false
        beginResult.stateOfType[Authorizing].amountAuthorized shouldBe entryAmount

        val debitResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(debitHold.hashCode(), accountIdToDebit, expectedDebitResultingBalance, holdTime)
        )
        debitResult.events shouldBe Seq(DebitHoldSucceeded(expectedDebitResultingBalance, holdTime))
        debitResult.stateOfType[Pending].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        debitResult.stateOfType[Pending].accountToDebit shouldBe accountIdToDebit
        debitResult.stateOfType[Pending].accountToCredit shouldBe accountIdToCredit
        debitResult.stateOfType[Pending].entryCode shouldBe entryCode
        debitResult.stateOfType[Pending].entryId shouldBe txnId
        debitResult.stateOfType[Pending].reversalPending shouldBe false
        debitResult.stateOfType[Pending].amountAuthorized shouldBe entryAmount
        debitResult.stateOfType[Pending].debitHoldTimestamp shouldBe holdTime

        val pendingResult = eventSourcedTestKit.runCommand(Capture(captureAmount, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        pendingResult.events shouldBe Seq(CaptureRequested(captureAmount))
        pendingResult.stateOfType[Crediting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        pendingResult.stateOfType[Crediting].accountToDebit shouldBe accountIdToDebit
        pendingResult.stateOfType[Crediting].accountToCredit shouldBe accountIdToCredit
        pendingResult.stateOfType[Crediting].entryCode shouldBe entryCode
        pendingResult.stateOfType[Crediting].entryId shouldBe txnId
        pendingResult.stateOfType[Crediting].reversalPending shouldBe false
        pendingResult.stateOfType[Crediting].amountAuthorized shouldBe entryAmount
        pendingResult.stateOfType[Crediting].captureAmount shouldBe captureAmount
        pendingResult.stateOfType[Crediting].debitHoldTimestamp shouldBe holdTime

        val creditResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(partialCredit.hashCode(), accountIdToCredit, expectedCreditResultingBalance, theTime)
        )
        creditResult.events shouldBe Seq(CreditSucceeded(expectedCreditResultingBalance))
        creditResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        creditResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posting].entryCode shouldBe entryCode
        creditResult.stateOfType[Posting].entryId shouldBe txnId
        creditResult.stateOfType[Posting].reversalPending shouldBe false
        creditResult.stateOfType[Posting].amountAuthorized shouldBe entryAmount
        creditResult.stateOfType[Posting].captureAmount shouldBe captureAmount
        creditResult.stateOfType[Posting].debitHoldTimestamp shouldBe holdTime

        creditResult.stateOfType[Posting]
      }

      "transition to Posted after DebitPostSucceeded" in {
        val debitPostedAccountResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubResultMessenger expects EntryPending(txnId, expectedDebitResultingBalance) once

          stubAccountMessenger expects (accountIdToCredit, partialCredit) once

          stubAccountMessenger expects (accountIdToDebit, partialPost) once

          stubResultMessenger expects EntrySuccessful(
            txnId,
            debitPostedAccountResultingBalance,
            expectedCreditResultingBalance
          ) once
        }

        given()

        val creditResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(partialPost.hashCode(), accountIdToDebit, debitPostedAccountResultingBalance, theTime)
        )
        creditResult.events shouldBe Seq(DebitPostSucceeded(debitPostedAccountResultingBalance))
        creditResult.stateOfType[Posted].debitedAccountResultingBalance shouldBe debitPostedAccountResultingBalance
        creditResult.stateOfType[Posted].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        creditResult.stateOfType[Posted].accountToDebit shouldBe accountIdToDebit
        creditResult.stateOfType[Posted].accountToCredit shouldBe accountIdToCredit
        creditResult.stateOfType[Posted].entryCode shouldBe entryCode
        creditResult.stateOfType[Posted].entryId shouldBe txnId
        creditResult.stateOfType[Posted].reversalPending shouldBe false
        creditResult.stateOfType[Posted].amountCaptured shouldBe captureAmount
      }

      "remain in Posting after DebitPostFailed and can be resumed to Posted" in {
        val debitPostedAccountResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

        inSequence {
          stubAccountMessenger expects (accountIdToDebit, debitHold) once

          stubResultMessenger expects EntryPending(txnId, expectedDebitResultingBalance) once

          stubAccountMessenger expects (accountIdToCredit, partialCredit) once

          stubAccountMessenger expects (accountIdToDebit, partialPost) once //"twice" doesn't work, strangely

          stubAccountMessenger expects (accountIdToDebit, partialPost) once

          stubResultMessenger expects EntrySuccessful(
            txnId,
            debitPostedAccountResultingBalance,
            expectedCreditResultingBalance
          ) once
        }

        val givenState = given()

        val creditResult = eventSourcedTestKit.runCommand(
          RejectAccounting(partialPost.hashCode(), accountIdToDebit, LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE)
        )
        creditResult.events shouldBe Seq(DebitPostFailed(LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString))
        creditResult.stateOfType[ResumablePosting].actualState shouldBe givenState

        val resumeResult = eventSourcedTestKit.runCommand(Resume(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        resumeResult.events shouldBe Seq(Resumed())
        resumeResult.stateOfType[Posting].debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        resumeResult.stateOfType[Posting].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        resumeResult.stateOfType[Posting].accountToDebit shouldBe accountIdToDebit
        resumeResult.stateOfType[Posting].accountToCredit shouldBe accountIdToCredit
        resumeResult.stateOfType[Posting].entryCode shouldBe entryCode
        resumeResult.stateOfType[Posting].entryId shouldBe txnId
        resumeResult.stateOfType[Posting].reversalPending shouldBe false
        resumeResult.stateOfType[Posting].amountAuthorized shouldBe entryAmount
        resumeResult.stateOfType[Posting].captureAmount shouldBe captureAmount
        resumeResult.stateOfType[Posting].debitHoldTimestamp shouldBe holdTime

        val postingResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(partialPost.hashCode(), accountIdToDebit, debitPostedAccountResultingBalance, theTime)
        )
        postingResult.events shouldBe Seq(DebitPostSucceeded(debitPostedAccountResultingBalance))
        postingResult.stateOfType[Posted].debitedAccountResultingBalance shouldBe debitPostedAccountResultingBalance
        postingResult.stateOfType[Posted].creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        postingResult.stateOfType[Posted].accountToDebit shouldBe accountIdToDebit
        postingResult.stateOfType[Posted].accountToCredit shouldBe accountIdToCredit
        postingResult.stateOfType[Posted].entryCode shouldBe entryCode
        postingResult.stateOfType[Posted].entryId shouldBe txnId
        postingResult.stateOfType[Posted].reversalPending shouldBe false
        postingResult.stateOfType[Posted].amountCaptured shouldBe captureAmount
      }
    }
  }

}
