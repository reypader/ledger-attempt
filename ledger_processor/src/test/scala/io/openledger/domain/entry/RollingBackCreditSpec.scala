package io.openledger.domain.entry

import io.openledger.LedgerError
import io.openledger.domain.entry.Entry.{Ack, TxnAck, apply => _, _}
import io.openledger.domain.entry.states._
import io.openledger.events._
import org.scalatest.Inside.inside

import scala.language.postfixOps

object RollingBackCreditSpec {
  def givenFromPosted(spec: AbstractEntrySpecBase): EntryState = {
    PostedSpec.given(spec)
    val reverseResult = spec.eventSourcedTestKit.runCommand(Reverse(spec.ackProbe.ref))
    spec.ackProbe.expectMessageType[TxnAck]

    reverseResult.stateOfType[RollingBackCredit]
  }
}

class RollingBackCreditSpec extends AbstractEntrySpecBase {
  "An Entry" when {

    "RollingBackCredit Entry" must {

      "transition to RollingBackDebit on DebitAdjustmentDone" in {
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
        }

        RollingBackCreditSpec.givenFromPosted(this)

        val acceptCreditReverseResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(
            debitAdjust.hashCode(),
            expectedAccountIdToCredit,
            expectedCreditReversedResultingBalance,
            reverseCreditTime
          )
        )
        acceptCreditReverseResult.events.size shouldBe 1
        inside(acceptCreditReverseResult.events.head) { case DebitAdjustmentDone(creditedAccountResultingBalance) =>
          creditedAccountResultingBalance shouldBe expectedCreditReversedResultingBalance
        }
        inside(acceptCreditReverseResult.stateOfType[RollingBackDebit]) {
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
            creditReversedResultingBalance shouldBe Some(expectedCreditReversedResultingBalance)
            code shouldBe None
        }
      }

      "remain in RollingBackCredit on DebitAdjustmentFailed and resume to RollingBackDebit" in {
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

          stubAccountMessenger expects (expectedAccountIdToCredit, debitAdjust) once

          stubAccountMessenger expects (expectedAccountIdToDebit, creditAdjust) once
        }

        val givenState = RollingBackCreditSpec.givenFromPosted(this)

        val rejectCreditReverseResult = eventSourcedTestKit.runCommand(
          RejectAccounting(
            debitAdjust.hashCode(),
            expectedAccountIdToCredit,
            LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE
          )
        )

        rejectCreditReverseResult.events.size shouldBe 1
        inside(rejectCreditReverseResult.events.head) { case DebitAdjustmentFailed(code) =>
          code shouldBe LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString
        }
        rejectCreditReverseResult.stateOfType[ResumableRollingBackCredit].actualState shouldBe givenState

        val resumeResult = eventSourcedTestKit.runCommand(Resume(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        resumeResult.events shouldBe Seq(Resumed())
        inside(resumeResult.stateOfType[RollingBackCredit]) {
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

        val acceptCreditReverseResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(
            debitAdjust.hashCode(),
            expectedAccountIdToCredit,
            expectedCreditReversedResultingBalance,
            reverseCreditTime
          )
        )
        acceptCreditReverseResult.events.size shouldBe 1
        inside(acceptCreditReverseResult.events.head) { case DebitAdjustmentDone(creditedAccountResultingBalance) =>
          creditedAccountResultingBalance shouldBe expectedCreditReversedResultingBalance
        }
        inside(acceptCreditReverseResult.stateOfType[RollingBackDebit]) {
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
            creditReversedResultingBalance shouldBe Some(expectedCreditReversedResultingBalance)
            code shouldBe None
        }
      }
    }
  }
}
