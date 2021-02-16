package io.openledger.domain.entry
import io.openledger.domain.account.Account.DebitCapture
import io.openledger.domain.entry.Entry.{Ack, Begin, TxnAck, apply => _, _}
import io.openledger.domain.entry.states.{Authorizing, _}
import io.openledger.events.{Started, _}
import io.openledger.{LedgerError, ResultingBalance}
import org.scalatest.Inside.inside

import java.time.Duration
import scala.language.postfixOps
object CapturingSpec {
  def given(spec: AbstractEntrySpecBase): EntryState = {
    CreditingSpec.given(spec)

    val acceptCreditResult = spec.eventSourcedTestKit.runCommand(
      AcceptAccounting(
        spec.fullCredit.hashCode(),
        spec.expectedAccountIdToCredit,
        spec.expectedCreditResultingBalance,
        spec.creditingTime
      )
    )

    acceptCreditResult.stateOfType[Capturing]
  }

  def givenPartial(spec: AbstractEntrySpecBase): EntryState = {
    CreditingSpec.givenFromPending(spec)

    val acceptCreditResult = spec.eventSourcedTestKit.runCommand(
      AcceptAccounting(
        spec.partialCredit.hashCode(),
        spec.expectedAccountIdToCredit,
        spec.expectedCreditResultingBalance,
        spec.creditingTime
      )
    )

    acceptCreditResult.stateOfType[Capturing]
  }
}
class CapturingSpec extends AbstractEntrySpecBase {
  "An Entry" when {

    "Capturing Entry" must {

      "transition to Posted after DebitCaptureSucceeded" in {
        val debitCapturedAccountResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAuth) once

          stubAccountMessenger expects (expectedAccountIdToCredit, fullCredit) once

          stubAccountMessenger expects (expectedAccountIdToDebit, fullPastCapture) once

          stubResultMessenger expects EntrySuccessful(
            expectedTxnId,
            debitCapturedAccountResultingBalance,
            expectedCreditResultingBalance
          ) once
        }

        CapturingSpec.given(this)

        val acceptCaptureResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(
            fullPastCapture.hashCode(),
            expectedAccountIdToDebit,
            debitCapturedAccountResultingBalance,
            captureTime
          )
        )

        acceptCaptureResult.events.size shouldBe 2
        inside(acceptCaptureResult.events.head) { case DebitCaptureSucceeded(debitedAccountResultingBalance) =>
          debitedAccountResultingBalance shouldBe debitCapturedAccountResultingBalance
        }
        acceptCaptureResult.events(1).getClass shouldBe classOf[io.openledger.events.Done]

        inside(acceptCaptureResult.stateOfType[Posted]) {
          case Posted(
                entryCode,
                entryId,
                accountToDebit,
                accountToCredit,
                amountCaptured,
                debitedAccountResultingBalance,
                creditedAccountResultingBalance
              ) =>
            debitedAccountResultingBalance shouldBe debitCapturedAccountResultingBalance
            creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
            accountToDebit shouldBe expectedAccountIdToDebit
            accountToCredit shouldBe expectedAccountIdToCredit
            entryCode shouldBe expectedEntryCode
            entryId shouldBe expectedTxnId
            amountCaptured shouldBe expectedEntryAmount
        }

      }

      "remain in Capturing after DebitCaptureFailed and resume to Posted" in {
        val debitCapturedAccountResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAuth) once

          stubAccountMessenger expects (expectedAccountIdToCredit, fullCredit) once

          stubAccountMessenger expects (expectedAccountIdToDebit, fullPastCapture) once //"twice" doesn't work, strangely

          stubAccountMessenger expects (expectedAccountIdToDebit, fullPastCapture) once

          stubResultMessenger expects EntrySuccessful(
            expectedTxnId,
            debitCapturedAccountResultingBalance,
            expectedCreditResultingBalance
          ) once
        }

        val givenState = CapturingSpec.given(this)

        val rejectCaptureResult = eventSourcedTestKit.runCommand(
          RejectAccounting(
            fullPastCapture.hashCode(),
            expectedAccountIdToDebit,
            LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE
          )
        )
        rejectCaptureResult.events shouldBe Seq(
          DebitCaptureFailed(LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString)
        )
        rejectCaptureResult.stateOfType[ResumableCapturing].actualState shouldBe givenState

        val resumeResult = eventSourcedTestKit.runCommand(Resume(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        resumeResult.events shouldBe Seq(Resumed())

        inside(resumeResult.stateOfType[Capturing]) {
          case Capturing(
                entryCode,
                entryId,
                accountToDebit,
                accountToCredit,
                amountAuthorized,
                captureAmount,
                debitedAccountResultingBalance,
                creditedAccountResultingBalance,
                debitAuthorizeTimestamp,
                reversalPending
              ) =>
            debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
            creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
            accountToDebit shouldBe expectedAccountIdToDebit
            accountToCredit shouldBe expectedAccountIdToCredit
            entryCode shouldBe expectedEntryCode
            entryId shouldBe expectedTxnId
            reversalPending shouldBe false
            amountAuthorized shouldBe expectedEntryAmount
            captureAmount shouldBe expectedEntryAmount
            debitAuthorizeTimestamp shouldBe authorizeTime
        }

        val acceptCaptureResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(
            fullPastCapture.hashCode(),
            expectedAccountIdToDebit,
            debitCapturedAccountResultingBalance,
            captureTime
          )
        )

        acceptCaptureResult.events.size shouldBe 2
        inside(acceptCaptureResult.events.head) { case DebitCaptureSucceeded(debitedAccountResultingBalance) =>
          debitedAccountResultingBalance shouldBe debitCapturedAccountResultingBalance
        }
        acceptCaptureResult.events(1).getClass shouldBe classOf[io.openledger.events.Done]

        inside(acceptCaptureResult.stateOfType[Posted]) {
          case Posted(
                entryCode,
                entryId,
                accountToDebit,
                accountToCredit,
                amountCaptured,
                debitedAccountResultingBalance,
                creditedAccountResultingBalance
              ) =>
            debitedAccountResultingBalance shouldBe debitCapturedAccountResultingBalance
            creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
            accountToDebit shouldBe expectedAccountIdToDebit
            accountToCredit shouldBe expectedAccountIdToCredit
            entryCode shouldBe expectedEntryCode
            entryId shouldBe expectedTxnId
            amountCaptured shouldBe expectedEntryAmount
        }
      }

      "transition to Posted then RollingBackCredit immediately after DebitCaptureSucceeded (reversal marked)" in {
        val debitCapturedAccountResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAuth) once

          stubAccountMessenger expects (expectedAccountIdToCredit, fullCredit) once

          stubAccountMessenger expects (expectedAccountIdToDebit, fullPastCapture) once
          // Note to future self: Do not emit a response if they've already requested a reversal.
          //        stubResultMessenger expects EntrySuccessful(
          //          expectedTxnId,
          //          debitCapturedAccountResultingBalance,
          //          expectedCreditResultingBalance
          //        ) once

          stubAccountMessenger expects (expectedAccountIdToCredit, debitAdjust) once
        }

        CapturingSpec.given(this)

        val prematureReverseResult = eventSourcedTestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        prematureReverseResult.events.size shouldBe 1
        prematureReverseResult.events.head.getClass shouldBe classOf[ReversalRequested]
        inside(prematureReverseResult.stateOfType[Capturing]) {
          case Capturing(
                entryCode,
                entryId,
                accountToDebit,
                accountToCredit,
                amountAuthorized,
                captureAmount,
                debitedAccountResultingBalance,
                creditedAccountResultingBalance,
                debitAuthorizeTimestamp,
                reversalPending
              ) =>
            debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
            creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
            accountToDebit shouldBe expectedAccountIdToDebit
            accountToCredit shouldBe expectedAccountIdToCredit
            entryCode shouldBe expectedEntryCode
            entryId shouldBe expectedTxnId
            reversalPending shouldBe true
            amountAuthorized shouldBe expectedEntryAmount
            captureAmount shouldBe expectedEntryAmount
            debitAuthorizeTimestamp shouldBe authorizeTime
        }

        val acceptCaptureResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(
            fullPastCapture.hashCode(),
            expectedAccountIdToDebit,
            debitCapturedAccountResultingBalance,
            captureTime
          )
        )

        acceptCaptureResult.events.size shouldBe 2
        inside(acceptCaptureResult.events.head) { case DebitCaptureSucceeded(debitedAccountResultingBalance) =>
          debitedAccountResultingBalance shouldBe debitCapturedAccountResultingBalance
        }
        acceptCaptureResult.events(1).getClass shouldBe classOf[ReversalRequested]
        //        inside(acceptCaptureResult.stateOfType[Posted]) {
        //          case Posted(
        //                entryCode,
        //                entryId,
        //                accountToDebit,
        //                accountToCredit,
        //                amountCaptured,
        //                debitedAccountResultingBalance,
        //                creditedAccountResultingBalance
        //              ) =>
        //            debitedAccountResultingBalance shouldBe debitCapturedAccountResultingBalance
        //            creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        //            accountToDebit shouldBe expectedAccountIdToDebit
        //            accountToCredit shouldBe expectedAccountIdToCredit
        //            entryCode shouldBe expectedEntryCode
        //            entryId shouldBe expectedTxnId
        //            amountCaptured shouldBe expectedEntryAmount
        //        }

        inside(acceptCaptureResult.stateOfType[RollingBackCredit]) {
          case RollingBackCredit(
                entryCode,
                entryId,
                accountToDebit,
                accountToCredit,
                creditedAmount,
                amountCaptured,
                code
              ) =>
            accountToDebit shouldBe expectedAccountIdToDebit
            accountToCredit shouldBe expectedAccountIdToCredit
            entryCode shouldBe expectedEntryCode
            entryId shouldBe expectedTxnId
            creditedAmount shouldBe expectedEntryAmount
            amountCaptured shouldBe Some(expectedEntryAmount)
            code shouldBe None
        }

      }

      "remain in Capturing after DebitCaptureFailed and resume to Posted then RollingBackCredit immediately (reversal marked)" in {
        val debitCapturedAccountResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAuth) once

          stubAccountMessenger expects (expectedAccountIdToCredit, fullCredit) once

          stubAccountMessenger expects (expectedAccountIdToDebit, fullPastCapture) once //This one is the first attempt that will be rejected

          stubResultMessenger expects CommandRejected(
            expectedTxnId,
            LedgerError.UNSUPPORTED_ENTRY_OPERATION_ON_CURRENT_STATE
          ) once

          stubAccountMessenger expects (expectedAccountIdToDebit, fullPastCapture) once //This one is the third attempt after rejection. Premature resume must be ignored
          // Note to future self: Do not emit a response if they've already requested a reversal.
          //        stubResultMessenger expects EntrySuccessful(
          //          expectedTxnId,
          //          debitCapturedAccountResultingBalance,
          //          expectedCreditResultingBalance
          //        ) once

          stubAccountMessenger expects (expectedAccountIdToCredit, debitAdjust) once
        }

        val givenState = CapturingSpec.given(this)

        val prematureResumeResult = eventSourcedTestKit.runCommand(Resume(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Nack
        prematureResumeResult.hasNoEvents shouldBe true
        prematureResumeResult.stateOfType[Capturing] shouldBe givenState

        val rejectCaptureResult = eventSourcedTestKit.runCommand(
          RejectAccounting(
            fullPastCapture.hashCode(),
            expectedAccountIdToDebit,
            LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE
          )
        )
        rejectCaptureResult.events shouldBe Seq(
          DebitCaptureFailed(LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString)
        )
        rejectCaptureResult.stateOfType[ResumableCapturing].actualState shouldBe givenState

        val prematureReverseResult = eventSourcedTestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        prematureReverseResult.events.size shouldBe 1
        prematureReverseResult.events.head.getClass shouldBe classOf[ReversalRequested]
        prematureReverseResult
          .stateOfType[ResumableCapturing]
          .actualState shouldBe givenState.asInstanceOf[Capturing].copy(reversalPending = true)

        val resumeResult = eventSourcedTestKit.runCommand(Resume(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        resumeResult.events shouldBe Seq(Resumed())
        inside(resumeResult.stateOfType[Capturing]) {
          case Capturing(
                entryCode,
                entryId,
                accountToDebit,
                accountToCredit,
                amountAuthorized,
                captureAmount,
                debitedAccountResultingBalance,
                creditedAccountResultingBalance,
                debitAuthorizeTimestamp,
                reversalPending
              ) =>
            debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
            creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
            accountToDebit shouldBe expectedAccountIdToDebit
            accountToCredit shouldBe expectedAccountIdToCredit
            entryCode shouldBe expectedEntryCode
            entryId shouldBe expectedTxnId
            reversalPending shouldBe true
            amountAuthorized shouldBe expectedEntryAmount
            captureAmount shouldBe expectedEntryAmount
            debitAuthorizeTimestamp shouldBe authorizeTime
        }

        val acceptCaptureResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(
            fullPastCapture.hashCode(),
            expectedAccountIdToDebit,
            debitCapturedAccountResultingBalance,
            captureTime
          )
        )

        acceptCaptureResult.events.size shouldBe 2
        inside(acceptCaptureResult.events.head) { case DebitCaptureSucceeded(debitedAccountResultingBalance) =>
          debitedAccountResultingBalance shouldBe debitCapturedAccountResultingBalance
        }
        acceptCaptureResult.events(1).getClass shouldBe classOf[ReversalRequested]
        //        inside(acceptCaptureResult.stateOfType[Posted]) {
        //          case Posted(
        //                entryCode,
        //                entryId,
        //                accountToDebit,
        //                accountToCredit,
        //                amountCaptured,
        //                debitedAccountResultingBalance,
        //                creditedAccountResultingBalance
        //              ) =>
        //            debitedAccountResultingBalance shouldBe debitCapturedAccountResultingBalance
        //            creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        //            accountToDebit shouldBe expectedAccountIdToDebit
        //            accountToCredit shouldBe expectedAccountIdToCredit
        //            entryCode shouldBe expectedEntryCode
        //            entryId shouldBe expectedTxnId
        //            amountCaptured shouldBe expectedEntryAmount
        //        }

        inside(acceptCaptureResult.stateOfType[RollingBackCredit]) {
          case RollingBackCredit(
                entryCode,
                entryId,
                accountToDebit,
                accountToCredit,
                creditedAmount,
                amountCaptured,
                code
              ) =>
            accountToDebit shouldBe expectedAccountIdToDebit
            accountToCredit shouldBe expectedAccountIdToCredit
            entryCode shouldBe expectedEntryCode
            entryId shouldBe expectedTxnId
            creditedAmount shouldBe expectedEntryAmount
            amountCaptured shouldBe Some(expectedEntryAmount)
            code shouldBe None
        }

      }
    }

    "Capturing (partial) Entry" must {

      "transition to Posted after DebitCaptureSucceeded" in {
        val debitCapturedAccountResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAuth) once

          stubResultMessenger expects EntryPending(expectedTxnId, expectedDebitResultingBalance) once

          stubAccountMessenger expects (expectedAccountIdToCredit, partialCredit) once

          stubAccountMessenger expects (expectedAccountIdToDebit, partialCapture) once

          stubResultMessenger expects EntrySuccessful(
            expectedTxnId,
            debitCapturedAccountResultingBalance,
            expectedCreditResultingBalance
          ) once
        }

        CapturingSpec.givenPartial(this)

        val acceptCaptureResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(
            partialCapture.hashCode(),
            expectedAccountIdToDebit,
            debitCapturedAccountResultingBalance,
            captureTime
          )
        )
        acceptCaptureResult.events.size shouldBe 2
        inside(acceptCaptureResult.events.head) { case DebitCaptureSucceeded(debitedAccountResultingBalance) =>
          debitedAccountResultingBalance shouldBe debitCapturedAccountResultingBalance
        }
        acceptCaptureResult.events(1).getClass shouldBe classOf[io.openledger.events.Done]

        inside(acceptCaptureResult.stateOfType[Posted]) {
          case Posted(
                entryCode,
                entryId,
                accountToDebit,
                accountToCredit,
                amountCaptured,
                debitedAccountResultingBalance,
                creditedAccountResultingBalance
              ) =>
            debitedAccountResultingBalance shouldBe debitCapturedAccountResultingBalance
            creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
            accountToDebit shouldBe expectedAccountIdToDebit
            accountToCredit shouldBe expectedAccountIdToCredit
            entryCode shouldBe expectedEntryCode
            entryId shouldBe expectedTxnId
            amountCaptured shouldBe expectedCaptureAmount
        }

      }

      "remain in Capturing after DebitCaptureFailed and can be resumed to Posted" in {
        val debitCapturedAccountResultingBalance = ResultingBalance(BigDecimal(9), BigDecimal(9))

        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAuth) once

          stubResultMessenger expects EntryPending(expectedTxnId, expectedDebitResultingBalance) once

          stubAccountMessenger expects (expectedAccountIdToCredit, partialCredit) once

          stubAccountMessenger expects (expectedAccountIdToDebit, partialCapture) once //"twice" doesn't work, strangely

          stubAccountMessenger expects (expectedAccountIdToDebit, partialCapture) once

          stubResultMessenger expects EntrySuccessful(
            expectedTxnId,
            debitCapturedAccountResultingBalance,
            expectedCreditResultingBalance
          ) once
        }

        val givenState = CapturingSpec.givenPartial(this)

        val creditResult = eventSourcedTestKit.runCommand(
          RejectAccounting(
            partialCapture.hashCode(),
            expectedAccountIdToDebit,
            LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE
          )
        )
        creditResult.events shouldBe Seq(DebitCaptureFailed(LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString))
        creditResult.stateOfType[ResumableCapturing].actualState shouldBe givenState

        val resumeResult = eventSourcedTestKit.runCommand(Resume(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        resumeResult.events shouldBe Seq(Resumed())
        inside(resumeResult.stateOfType[Capturing]) {
          case Capturing(
                entryCode,
                entryId,
                accountToDebit,
                accountToCredit,
                amountAuthorized,
                captureAmount,
                debitedAccountResultingBalance,
                creditedAccountResultingBalance,
                debitAuthorizeTimestamp,
                reversalPending
              ) =>
            debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
            creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
            accountToDebit shouldBe expectedAccountIdToDebit
            accountToCredit shouldBe expectedAccountIdToCredit
            entryCode shouldBe expectedEntryCode
            entryId shouldBe expectedTxnId
            reversalPending shouldBe false
            amountAuthorized shouldBe expectedEntryAmount
            captureAmount shouldBe expectedCaptureAmount
            debitAuthorizeTimestamp shouldBe authorizeTime
        }

        val acceptCaptureResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(
            partialCapture.hashCode(),
            expectedAccountIdToDebit,
            debitCapturedAccountResultingBalance,
            captureTime
          )
        )
        acceptCaptureResult.events.size shouldBe 2
        inside(acceptCaptureResult.events.head) { case DebitCaptureSucceeded(debitedAccountResultingBalance) =>
          debitedAccountResultingBalance shouldBe debitCapturedAccountResultingBalance
        }
        acceptCaptureResult.events(1).getClass shouldBe classOf[io.openledger.events.Done]

        inside(acceptCaptureResult.stateOfType[Posted]) {
          case Posted(
                entryCode,
                entryId,
                accountToDebit,
                accountToCredit,
                amountCaptured,
                debitedAccountResultingBalance,
                creditedAccountResultingBalance
              ) =>
            debitedAccountResultingBalance shouldBe debitCapturedAccountResultingBalance
            creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
            accountToDebit shouldBe expectedAccountIdToDebit
            accountToCredit shouldBe expectedAccountIdToCredit
            entryCode shouldBe expectedEntryCode
            entryId shouldBe expectedTxnId
            amountCaptured shouldBe expectedCaptureAmount
        }
      }
    }
  }
}
