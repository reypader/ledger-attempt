package io.openledger.domain.entry

import io.openledger.domain.account.Account.DebitCapture
import io.openledger.domain.entry.Entry.{Ack, Begin, TxnAck, apply => _, _}
import io.openledger.domain.entry.states.{Authorizing, _}
import io.openledger.events.{Started, _}
import io.openledger.{LedgerError, ResultingBalance}
import org.scalatest.Inside.inside

import java.time.Duration
import scala.language.postfixOps

object CreditingSpec {
  def given(spec: AbstractEntrySpecBase): EntryState = {
    AuthorizingSpec.given(spec)
    val debitResult = spec.eventSourcedTestKit.runCommand(
      AcceptAccounting(
        spec.debitAuth.hashCode(),
        spec.expectedAccountIdToDebit,
        spec.expectedDebitResultingBalance,
        spec.authorizeTime
      )
    )

    debitResult.stateOfType[Crediting]
  }
  def givenFromPending(spec: AbstractEntrySpecBase): EntryState = {
    PendingSpec.given(spec)
    val debitResult = spec.eventSourcedTestKit.runCommand(
      Capture(spec.expectedCaptureAmount, spec.ackProbe.ref)
    )
    spec.ackProbe.expectMessageType[TxnAck]

    debitResult.stateOfType[Crediting]
  }
}

class CreditingSpec extends AbstractEntrySpecBase {
  "An Entry" when {

    "Crediting Entry" must {

      "transition to Capturing after CreditingSucceeded" in {
        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAuth) once

          stubAccountMessenger expects (expectedAccountIdToCredit, fullCredit) once

          stubAccountMessenger expects (expectedAccountIdToDebit, fullPastCapture) once
        }

        CreditingSpec.given(this)

        val creditResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(
            fullCredit.hashCode(),
            expectedAccountIdToCredit,
            expectedCreditResultingBalance,
            creditingTime
          )
        )
        creditResult.events.size shouldBe 1
        inside(creditResult.events.head) { case CreditSucceeded(creditedAccountResultingBalance) =>
          creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        }

        inside(creditResult.stateOfType[Capturing]) {
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

      }

      "transition to RollingBackDebit after CreditingFailed" in {
        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAuth) once

          stubAccountMessenger expects (expectedAccountIdToCredit, fullCredit) once

          stubAccountMessenger expects (expectedAccountIdToDebit, fullRelease) once
        }

        CreditingSpec.given(this)

        val creditResult = eventSourcedTestKit.runCommand(
          RejectAccounting(
            fullCredit.hashCode(),
            expectedAccountIdToCredit,
            LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE
          )
        )
        creditResult.events.size shouldBe 1
        inside(creditResult.events.head) { case CreditFailed(code) =>
          code shouldBe LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString
        }

        inside(creditResult.stateOfType[RollingBackDebit]) {
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
            amountCaptured shouldBe None
            creditReversedResultingBalance shouldBe None
            code shouldBe Some(LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString)
        }

      }

      "transition to Capturing after CreditingSucceeded (reversal marked)" in {
        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAuth) once

          stubAccountMessenger expects (expectedAccountIdToCredit, fullCredit) once

          stubAccountMessenger expects (expectedAccountIdToDebit, fullPastCapture) once
        }

        CreditingSpec.given(this)

        val prematureReverseResult = eventSourcedTestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        prematureReverseResult.events.size shouldBe 1
        prematureReverseResult.events.head.getClass shouldBe classOf[ReversalRequested]
        inside(prematureReverseResult.stateOfType[Crediting]) {
          case Crediting(
                entryCode,
                entryId,
                accountToDebit,
                accountToCredit,
                amountAuthorized,
                captureAmount,
                debitedAccountResultingBalance,
                debitAuthorizeTimestamp,
                reversalPending
              ) =>
            debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
            accountToDebit shouldBe expectedAccountIdToDebit
            accountToCredit shouldBe expectedAccountIdToCredit
            entryCode shouldBe expectedEntryCode
            entryId shouldBe expectedTxnId
            reversalPending shouldBe true
            amountAuthorized shouldBe expectedEntryAmount
            captureAmount shouldBe expectedEntryAmount
            debitAuthorizeTimestamp shouldBe authorizeTime
        }

        val acceptCreditResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(
            fullCredit.hashCode(),
            expectedAccountIdToCredit,
            expectedCreditResultingBalance,
            creditingTime
          )
        )
        acceptCreditResult.events.size shouldBe 1
        inside(acceptCreditResult.events.head) { case CreditSucceeded(creditedAccountResultingBalance) =>
          creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        }
        inside(acceptCreditResult.stateOfType[Capturing]) {
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

      }

      "transition to RollingBackDebit after CreditingFailed (reversal marked)" in {
        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAuth) once

          stubAccountMessenger expects (expectedAccountIdToCredit, fullCredit) once

          stubAccountMessenger expects (expectedAccountIdToDebit, fullRelease) once
        }

        CreditingSpec.given(this)

        val reverseResult = eventSourcedTestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        reverseResult.events.size shouldBe 1
        reverseResult.events.head.getClass shouldBe classOf[ReversalRequested]
        inside(reverseResult.stateOfType[Crediting]) {
          case Crediting(
                entryCode,
                entryId,
                accountToDebit,
                accountToCredit,
                amountAuthorized,
                captureAmount,
                debitedAccountResultingBalance,
                debitAuthorizeTimestamp,
                reversalPending
              ) =>
            debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
            accountToDebit shouldBe expectedAccountIdToDebit
            accountToCredit shouldBe expectedAccountIdToCredit
            entryCode shouldBe expectedEntryCode
            entryId shouldBe expectedTxnId
            reversalPending shouldBe true
            amountAuthorized shouldBe expectedEntryAmount
            captureAmount shouldBe expectedEntryAmount
            debitAuthorizeTimestamp shouldBe authorizeTime
        }

        val rejectCreditResult = eventSourcedTestKit.runCommand(
          RejectAccounting(
            fullCredit.hashCode(),
            expectedAccountIdToCredit,
            LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE
          )
        )
        rejectCreditResult.events.size shouldBe 1
        inside(rejectCreditResult.events.head) { case CreditFailed(code) =>
          code shouldBe LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString
        }

        inside(rejectCreditResult.stateOfType[RollingBackDebit]) {
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
            amountCaptured shouldBe None
            creditReversedResultingBalance shouldBe None
            code shouldBe Some(LedgerError.DEBIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString)
        }
      }
    }

    "Crediting (partial) Entry" must {

      "transition to Capturing after CreditingSucceeded" in {
        val expectedCreditResultingBalance: ResultingBalance = ResultingBalance(BigDecimal(4), BigDecimal(5))
        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAuth) once

          stubResultMessenger expects EntryPending(expectedTxnId, expectedDebitResultingBalance) once

          stubAccountMessenger expects (expectedAccountIdToCredit, partialCredit) once

          stubAccountMessenger expects (expectedAccountIdToDebit, partialCapture) once
        }

        CreditingSpec.givenFromPending(this)

        val acceptCreditResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(
            partialCredit.hashCode(),
            expectedAccountIdToCredit,
            expectedCreditResultingBalance,
            creditingTime
          )
        )

        acceptCreditResult.events.size shouldBe 1
        inside(acceptCreditResult.events.head) { case CreditSucceeded(creditedAccountResultingBalance) =>
          creditedAccountResultingBalance shouldBe expectedCreditResultingBalance
        }

        inside(acceptCreditResult.stateOfType[Capturing]) {
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
      }

      "transition to RollingBackDebit after CreditingFailed" in {
        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAuth) once

          stubResultMessenger expects EntryPending(expectedTxnId, expectedDebitResultingBalance) once

          stubAccountMessenger expects (expectedAccountIdToCredit, partialCredit) once

          stubAccountMessenger expects (expectedAccountIdToDebit, fullRelease) once
        }

        CreditingSpec.givenFromPending(this)

        val rejectCreditResult = eventSourcedTestKit.runCommand(
          RejectAccounting(
            partialCredit.hashCode(),
            expectedAccountIdToCredit,
            LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE
          )
        )

        rejectCreditResult.events.size shouldBe 1
        inside(rejectCreditResult.events.head) { case CreditFailed(code) =>
          code shouldBe LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString
        }

        inside(rejectCreditResult.stateOfType[RollingBackDebit]) {
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
            amountCaptured shouldBe None
            creditReversedResultingBalance shouldBe None
            code shouldBe Some(LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString)
        }
      }
    }
  }
}
