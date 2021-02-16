package io.openledger.domain.entry

import io.openledger.domain.account.Account.DebitCapture
import io.openledger.domain.entry.Entry.{Ack, Begin, TxnAck, apply => _, _}
import io.openledger.domain.entry.states.{Authorizing, _}
import io.openledger.events.{Started, _}
import io.openledger.{LedgerError, ResultingBalance}
import org.scalatest.Inside.inside

import java.time.Duration
import scala.language.postfixOps
object PostedSpec {
  def given(spec: AbstractEntrySpecBase): EntryState = {
    CapturingSpec.given(spec)

    val acceptCaptureResult = spec.eventSourcedTestKit.runCommand(
      AcceptAccounting(
        spec.fullCapture.hashCode(),
        spec.expectedAccountIdToDebit,
        spec.expecteddebitCapturedBalance,
        spec.captureTime
      )
    )

    acceptCaptureResult.stateOfType[Posted]
  }
}
class PostedSpec extends AbstractEntrySpecBase {
  "An Entry" when {

    "Posted Entry" must {

      "transition to RollingBackCredit on ReversalRequested" in {
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
        }

        PostedSpec.given(this)

        val reverseResult = eventSourcedTestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        reverseResult.events.size shouldBe 1
        reverseResult.events.head.getClass shouldBe classOf[ReversalRequested]

        inside(reverseResult.stateOfType[RollingBackCredit]) {
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
  }
}
