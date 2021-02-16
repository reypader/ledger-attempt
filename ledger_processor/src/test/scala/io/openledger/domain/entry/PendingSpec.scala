package io.openledger.domain.entry

import io.openledger.LedgerError
import io.openledger.domain.entry.Entry.{Ack, TxnAck, apply => _, _}
import io.openledger.domain.entry.states._
import io.openledger.events._
import org.scalatest.Inside.inside

import scala.language.postfixOps
object PendingSpec {
  def given(spec: AbstractEntrySpecBase): EntryState = {
    AuthorizingSpec.givenAuthOnly(spec)

    val debitResult = spec.eventSourcedTestKit.runCommand(
      AcceptAccounting(
        spec.debitAuth.hashCode(),
        spec.expectedAccountIdToDebit,
        spec.expectedDebitResultingBalance,
        spec.authorizeTime
      )
    )

    debitResult.stateOfType[Pending]
  }
}
class PendingSpec extends AbstractEntrySpecBase {
  "An Entry" when {

    "Pending Entry" must {

      "transition to Crediting (partial) after CaptureRequested" in {

        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAuth) once

          stubResultMessenger expects EntryPending(expectedTxnId, expectedDebitResultingBalance) once

          stubAccountMessenger expects (expectedAccountIdToCredit, partialCredit) once
        }

        PendingSpec.given(this)

        val captureResult = eventSourcedTestKit.runCommand(Capture(expectedCaptureAmount, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        captureResult.events shouldBe Seq(CaptureRequested(expectedCaptureAmount))
        captureResult.events.size shouldBe 1
        inside(captureResult.events.head) { case CaptureRequested(captureAmount) =>
          captureAmount shouldBe expectedCaptureAmount
        }

        inside(captureResult.stateOfType[Crediting]) {
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
            reversalPending shouldBe false
            amountAuthorized shouldBe expectedEntryAmount
            captureAmount shouldBe expectedCaptureAmount
            debitAuthorizeTimestamp shouldBe authorizeTime
        }

      }

      "transition to RollingBackDebit after ReverseRequested" in {

        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAuth) once

          stubResultMessenger expects EntryPending(expectedTxnId, expectedDebitResultingBalance) once

          stubAccountMessenger expects (expectedAccountIdToDebit, fullRelease) once

        }

        PendingSpec.given(this)

        val debitResult = eventSourcedTestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        debitResult.events.size shouldBe 1
        debitResult.events.head.getClass shouldBe classOf[ReversalRequested]

        inside(debitResult.stateOfType[RollingBackDebit]) {
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
            code shouldBe None
            creditReversedResultingBalance shouldBe None
        }
      }

      "do nothing on over-DebitCapture" in {
        val captureAmount: BigDecimal = BigDecimal(100.00001)

        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAuth) once

          stubResultMessenger expects EntryPending(expectedTxnId, expectedDebitResultingBalance) once

          stubResultMessenger expects CaptureRejected(expectedTxnId, LedgerError.CAPTURE_MORE_THAN_AUTHORIZED) once
        }

        val givenState = PendingSpec.given(this)

        val pendingResult = eventSourcedTestKit.runCommand(Capture(captureAmount, ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        pendingResult.hasNoEvents shouldBe true
        pendingResult.stateOfType[Pending] shouldBe givenState
      }
    }
  }
}
