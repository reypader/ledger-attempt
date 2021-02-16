package io.openledger.domain.entry

import io.openledger.domain.entry.Entry.{Ack, TxnAck, apply => _, _}
import io.openledger.domain.entry.states._
import io.openledger.events._
import io.openledger.{LedgerError, ResultingBalance}
import org.scalatest.Inside.inside

import scala.language.postfixOps
object RollingBackDebitSpec {

  def givenFromCrediting(spec: AbstractEntrySpecBase): EntryState = {
    CreditingSpec.given(spec)

    val creditResult = spec.eventSourcedTestKit.runCommand(
      RejectAccounting(
        spec.fullCredit.hashCode(),
        spec.expectedAccountIdToCredit,
        LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE
      )
    )

    creditResult.stateOfType[RollingBackDebit]
  }

  def givenFromRollingBackCredit(spec: AbstractEntrySpecBase): EntryState = {
    RollingBackCreditSpec.givenFromPosted(spec)

    val reverseCreditResult = spec.eventSourcedTestKit.runCommand(
      AcceptAccounting(
        spec.debitAdjust.hashCode(),
        spec.expectedAccountIdToCredit,
        spec.expectedCreditReversedResultingBalance,
        spec.reverseCreditTime
      )
    )

    reverseCreditResult.stateOfType[RollingBackDebit]
  }
}
class RollingBackDebitSpec extends AbstractEntrySpecBase {
  "An Entry" when {

    "RollingBackDebit due to Error" must {

      "transition to Failed after CreditAdjustmentDone (DebitRelease)" in {
        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAuth) once

          stubAccountMessenger expects (expectedAccountIdToCredit, fullCredit) once

          stubAccountMessenger expects (expectedAccountIdToDebit, fullRelease) once

          stubResultMessenger expects EntryFailed(expectedTxnId, LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE) once
        }

        RollingBackDebitSpec.givenFromCrediting(this)

        val acceptDebitReverseResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(
            fullRelease.hashCode(),
            expectedAccountIdToDebit,
            expectedDebitReversedResultingBalance,
            reverseDebitTime
          )
        )
        acceptDebitReverseResult.events.size shouldBe 2
        inside(acceptDebitReverseResult.events.head) { case CreditAdjustmentDone(debitedAccountResultingBalance) =>
          debitedAccountResultingBalance shouldBe expectedDebitReversedResultingBalance
        }
        acceptDebitReverseResult.events(1).getClass shouldBe classOf[io.openledger.events.Done]
        inside(acceptDebitReverseResult.stateOfType[Failed]) { case Failed(entryCode, entryId, code) =>
          code shouldBe LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString
          entryCode shouldBe expectedEntryCode
          entryId shouldBe expectedTxnId
        }
      }

      "remain in RollingBackDebit on CreditAdjustmentFailed then resume to Failed" in {
        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAuth) once

          stubAccountMessenger expects (expectedAccountIdToCredit, fullCredit) once

          stubAccountMessenger expects (expectedAccountIdToDebit, fullRelease) once

          stubAccountMessenger expects (expectedAccountIdToDebit, fullRelease) once

          stubResultMessenger expects EntryFailed(expectedTxnId, LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE) once
        }

        val givenState = RollingBackDebitSpec.givenFromCrediting(this)

        val rejectDebitReverseResult = eventSourcedTestKit.runCommand(
          RejectAccounting(
            fullRelease.hashCode(),
            expectedAccountIdToDebit,
            LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE
          )
        )
        rejectDebitReverseResult.events shouldBe Seq(
          CreditAdjustmentFailed(LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString)
        )
        rejectDebitReverseResult.stateOfType[ResumableRollingBackDebit].actualState shouldBe givenState

        val resumeResult = eventSourcedTestKit.runCommand(Resume(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack

        resumeResult.events shouldBe Seq(Resumed())

        inside(resumeResult.stateOfType[RollingBackDebit]) {
          case RollingBackDebit(
                entryCode,
                entryId,
                accountToDebit,
                accountToCredit,
                authorizedAmount,
                amountCaptured,
                code,
                creditReversedResultingBalance
              ) =>
            code shouldBe Some(LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString)
            accountToDebit shouldBe expectedAccountIdToDebit
            accountToCredit shouldBe expectedAccountIdToCredit
            entryCode shouldBe expectedEntryCode
            entryId shouldBe expectedTxnId
            authorizedAmount shouldBe expectedEntryAmount
            amountCaptured shouldBe None
            creditReversedResultingBalance shouldBe None
        }

        val acceptDebitReverseResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(
            fullRelease.hashCode(),
            expectedAccountIdToDebit,
            expectedDebitReversedResultingBalance,
            reverseDebitTime
          )
        )

        acceptDebitReverseResult.events.size shouldBe 2
        inside(acceptDebitReverseResult.events.head) { case CreditAdjustmentDone(debitedAccountResultingBalance) =>
          debitedAccountResultingBalance shouldBe expectedDebitReversedResultingBalance
        }
        acceptDebitReverseResult.events(1).getClass shouldBe classOf[io.openledger.events.Done]
        inside(acceptDebitReverseResult.stateOfType[Failed]) { case Failed(entryCode, entryId, code) =>
          code shouldBe LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString
          entryCode shouldBe expectedEntryCode
          entryId shouldBe expectedTxnId
        }

      }
    }
    "RollingBackDebit due to Reversal" must {
//    val expectedDebitReversedResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))
//    val expectedCreditResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4), BigDecimal(5))
//    val expectedCreditReversedResultingBalance: ResultingBalance =
//      ResultingBalance(BigDecimal(4.75), BigDecimal(5.75))
//    val expectedDebitReversedResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4.25), BigDecimal(5.25))
//    val expecteddebitCapturedBalance: ResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

      "transition to Reversed after CreditAdjustmentDone" in {
        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAuth) once

          stubAccountMessenger expects (expectedAccountIdToCredit, fullCredit) once

          stubAccountMessenger expects (expectedAccountIdToDebit, fullCapture) once

          stubResultMessenger expects EntrySuccessful(
            expectedTxnId,
            expecteddebitCapturedBalance,
            expectedCreditResultingBalance
          ) once

          stubAccountMessenger expects (expectedAccountIdToCredit, debitAdjust) once

          stubAccountMessenger expects (expectedAccountIdToDebit, creditAdjust) once

          stubResultMessenger expects EntryReversed(
            expectedTxnId,
            expectedDebitReversedResultingBalance,
            Some(expectedCreditReversedResultingBalance)
          ) once
        }

        RollingBackDebitSpec.givenFromRollingBackCredit(this)

        val acceptDebitReverseResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(
            creditAdjust.hashCode(),
            expectedAccountIdToDebit,
            expectedDebitReversedResultingBalance,
            reverseDebitTime
          )
        )
        acceptDebitReverseResult.events.size shouldBe 2
        inside(acceptDebitReverseResult.events.head) { case CreditAdjustmentDone(debitedAccountResultingBalance) =>
          debitedAccountResultingBalance shouldBe expectedDebitReversedResultingBalance
        }
        acceptDebitReverseResult.events(1).getClass shouldBe classOf[io.openledger.events.Done]
        inside(acceptDebitReverseResult.stateOfType[Reversed]) {
          case Reversed(
                entryCode,
                entryId,
                accountToDebit,
                accountToCredit,
                debitReversedResultingBalance,
                creditReversedResultingBalance
              ) =>
            entryCode shouldBe expectedEntryCode
            entryId shouldBe expectedTxnId
            accountToDebit shouldBe expectedAccountIdToDebit
            accountToCredit shouldBe expectedAccountIdToCredit
            creditReversedResultingBalance shouldBe Some(expectedCreditReversedResultingBalance)
            debitReversedResultingBalance shouldBe expectedDebitReversedResultingBalance
        }
      }

      "remain in RollingBackDebit on CreditAdjustmentFailed then resumed to Reversed" in {
        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAuth) once

          stubAccountMessenger expects (expectedAccountIdToCredit, fullCredit) once

          stubAccountMessenger expects (expectedAccountIdToDebit, fullCapture) once

          stubResultMessenger expects EntrySuccessful(
            expectedTxnId,
            expecteddebitCapturedBalance,
            expectedCreditResultingBalance
          ) once

          stubAccountMessenger expects (expectedAccountIdToCredit, debitAdjust) once

          stubAccountMessenger expects (expectedAccountIdToDebit, creditAdjust) once

          stubAccountMessenger expects (expectedAccountIdToDebit, creditAdjust) once

          stubResultMessenger expects EntryReversed(
            expectedTxnId,
            expectedDebitReversedResultingBalance,
            Some(expectedCreditReversedResultingBalance)
          ) once
        }

        val givenState = RollingBackDebitSpec.givenFromRollingBackCredit(this)

        val creditResult = eventSourcedTestKit.runCommand(
          RejectAccounting(
            creditAdjust.hashCode(),
            expectedAccountIdToDebit,
            LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE
          )
        )
        creditResult.events shouldBe Seq(
          CreditAdjustmentFailed(LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString)
        )
        creditResult.stateOfType[ResumableRollingBackDebit].actualState shouldBe givenState

        val resumeResult = eventSourcedTestKit.runCommand(Resume(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        resumeResult.events shouldBe Seq(Resumed())

        inside(resumeResult.stateOfType[RollingBackDebit]) {
          case RollingBackDebit(
                entryCode,
                entryId,
                accountToDebit,
                accountToCredit,
                authorizedAmount,
                amountCaptured,
                code,
                creditReversedResultingBalance
              ) =>
            accountToDebit shouldBe expectedAccountIdToDebit
            accountToCredit shouldBe expectedAccountIdToCredit
            entryCode shouldBe expectedEntryCode
            entryId shouldBe expectedTxnId
            authorizedAmount shouldBe expectedEntryAmount
            amountCaptured shouldBe Some(expectedEntryAmount)
            creditReversedResultingBalance shouldBe Some(
              expectedCreditReversedResultingBalance
            )
            code shouldBe None
        }

        val acceptDebitReverseResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(
            creditAdjust.hashCode(),
            expectedAccountIdToDebit,
            expectedDebitReversedResultingBalance,
            reverseDebitTime
          )
        )
        acceptDebitReverseResult.events.size shouldBe 2
        inside(acceptDebitReverseResult.events.head) { case CreditAdjustmentDone(debitedAccountResultingBalance) =>
          debitedAccountResultingBalance shouldBe expectedDebitReversedResultingBalance
        }
        acceptDebitReverseResult.events(1).getClass shouldBe classOf[io.openledger.events.Done]
        inside(acceptDebitReverseResult.stateOfType[Reversed]) {
          case Reversed(
                entryCode,
                entryId,
                accountToDebit,
                accountToCredit,
                debitReversedResultingBalance,
                creditReversedResultingBalance
              ) =>
            entryCode shouldBe expectedEntryCode
            entryId shouldBe expectedTxnId
            accountToDebit shouldBe expectedAccountIdToDebit
            accountToCredit shouldBe expectedAccountIdToCredit
            creditReversedResultingBalance shouldBe Some(expectedCreditReversedResultingBalance)
            debitReversedResultingBalance shouldBe expectedDebitReversedResultingBalance
        }

      }
    }
  }
}
