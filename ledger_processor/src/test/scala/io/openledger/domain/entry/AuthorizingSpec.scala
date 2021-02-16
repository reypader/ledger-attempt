package io.openledger.domain.entry

import io.openledger.LedgerError
import io.openledger.domain.entry.Entry.{Ack, Begin, TxnAck, apply => _, _}
import io.openledger.domain.entry.states.{Authorizing, _}
import io.openledger.events._
import org.scalatest.Inside.inside

import scala.language.postfixOps

object AuthorizingSpec {
  def given(spec: AbstractEntrySpecBase): EntryState = {
    ReadySpec.given(spec)
    val beginResult = spec.eventSourcedTestKit.runCommand(
      Begin(
        spec.expectedEntryCode,
        spec.expectedAccountIdToDebit,
        spec.expectedAccountIdToCredit,
        spec.expectedEntryAmount,
        spec.ackProbe.ref
      )
    )
    spec.ackProbe.expectMessageType[TxnAck]
    beginResult.stateOfType[Authorizing]
  }
  def givenAuthOnly(spec: AbstractEntrySpecBase): EntryState = {
    ReadySpec.given(spec)
    val beginResult = spec.eventSourcedTestKit.runCommand(
      Begin(
        spec.expectedEntryCode,
        spec.expectedAccountIdToDebit,
        spec.expectedAccountIdToCredit,
        spec.expectedEntryAmount,
        spec.ackProbe.ref,
        authOnly = true
      )
    )
    spec.ackProbe.expectMessageType[TxnAck]
    beginResult.stateOfType[Authorizing]
  }
}

class AuthorizingSpec extends AbstractEntrySpecBase {
  "An Entry" when {

    "Authorizing Entry" must {

      "transition to Crediting after DebitAuthorizeSucceeded" in {
        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAuth) once

          stubAccountMessenger expects (expectedAccountIdToCredit, fullCredit) once
        }

        AuthorizingSpec.given(this)

        val acceptAuthResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(
            debitAuth.hashCode(),
            expectedAccountIdToDebit,
            expectedDebitResultingBalance,
            authorizeTime
          )
        )
        acceptAuthResult.events.size shouldBe 1
        inside(acceptAuthResult.events.head) {
          case DebitAuthorizeSucceeded(debitedAccountResultingBalance, timestamp) =>
            debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
            timestamp shouldBe authorizeTime
        }

        inside(acceptAuthResult.stateOfType[Crediting]) {
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
            captureAmount shouldBe expectedEntryAmount
            debitAuthorizeTimestamp shouldBe authorizeTime
        }

      }

      "transition to Failed after DebitAuthorizeFailed" in {
        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAuth) once

          stubResultMessenger expects EntryFailed(expectedTxnId, LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE) once
        }

        AuthorizingSpec.given(this)

        val debitResult = eventSourcedTestKit.runCommand(
          RejectAccounting(
            debitAuth.hashCode(),
            expectedAccountIdToDebit,
            LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE
          )
        )

        debitResult.events.size shouldBe 2
        inside(debitResult.events.head) { case DebitAuthorizeFailed(code) =>
          code shouldBe LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString
        }
        debitResult.events(1).getClass shouldBe classOf[io.openledger.events.Done]

        inside(debitResult.stateOfType[Failed]) { case Failed(entryCode, entryId, code) =>
          code shouldBe LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString
          entryCode shouldBe expectedEntryCode
          entryId shouldBe expectedTxnId
        }
      }

      "transition to Crediting after DebitAuthorizeSucceeded (reversal marked)" in {
        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAuth) once

          stubAccountMessenger expects (expectedAccountIdToCredit, fullCredit) once
        }

        AuthorizingSpec.given(this)

        val reverseResult = eventSourcedTestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        reverseResult.events.size shouldBe 1
        inside(reverseResult.events.head) { case ReversalRequested(_) =>
        }

        inside(reverseResult.stateOfType[Authorizing]) {
          case Authorizing(
                entryCode,
                entryId,
                accountToDebit,
                accountToCredit,
                amountAuthorized,
                authOnly,
                reversalPending
              ) =>
            accountToDebit shouldBe expectedAccountIdToDebit
            accountToCredit shouldBe expectedAccountIdToCredit
            entryCode shouldBe expectedEntryCode
            entryId shouldBe expectedTxnId
            reversalPending shouldBe true
            authOnly shouldBe false
            amountAuthorized shouldBe expectedEntryAmount
        }

        val debitResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(
            debitAuth.hashCode(),
            expectedAccountIdToDebit,
            expectedDebitResultingBalance,
            authorizeTime
          )
        )
        debitResult.events.size shouldBe 1
        inside(debitResult.events.head) { case DebitAuthorizeSucceeded(debitedAccountResultingBalance, timestamp) =>
          debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
          timestamp shouldBe authorizeTime
        }

        inside(debitResult.stateOfType[Crediting]) {
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
      }

      "transition to Failed after DebitAuthorizeFailed (reversal marked)" in {
        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAuth) once

          stubResultMessenger expects EntryFailed(expectedTxnId, LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE) once
        }

        AuthorizingSpec.given(this)

        val reverseResult = eventSourcedTestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        reverseResult.events.size shouldBe 1
        inside(reverseResult.events.head) { case ReversalRequested(_) =>
        }

        inside(reverseResult.stateOfType[Authorizing]) {
          case Authorizing(
                entryCode,
                entryId,
                accountToDebit,
                accountToCredit,
                amountAuthorized,
                authOnly,
                reversalPending
              ) =>
            accountToDebit shouldBe expectedAccountIdToDebit
            accountToCredit shouldBe expectedAccountIdToCredit
            entryCode shouldBe expectedEntryCode
            entryId shouldBe expectedTxnId
            reversalPending shouldBe true
            authOnly shouldBe false
            amountAuthorized shouldBe expectedEntryAmount
        }

        val debitResult = eventSourcedTestKit.runCommand(
          RejectAccounting(
            debitAuth.hashCode(),
            expectedAccountIdToDebit,
            LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE
          )
        )
        inside(debitResult.stateOfType[Failed]) { case Failed(entryCode, entryId, code) =>
          code shouldBe LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString
          entryCode shouldBe expectedEntryCode
          entryId shouldBe expectedTxnId
        }

      }
    }

    "Authorizing (auth only) Entry" must {

      "transition to Pending after DebitAuthorizeSucceeded" in {

        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAuth) once

          stubResultMessenger expects EntryPending(expectedTxnId, expectedDebitResultingBalance) once
        }

        AuthorizingSpec.givenAuthOnly(this)

        val acceptAuthResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(debitAuth.hashCode(), expectedAccountIdToDebit, expectedDebitResultingBalance, authorizeTime)
        )
        acceptAuthResult.events.size shouldBe 2
        inside(acceptAuthResult.events.head) {
          case DebitAuthorizeSucceeded(debitedAccountResultingBalance, timestamp) =>
            debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
            timestamp shouldBe authorizeTime
        }
        acceptAuthResult.events(1).getClass shouldBe classOf[Suspended]
        inside(acceptAuthResult.stateOfType[Pending]) {
          case Pending(
                entryCode,
                entryId,
                accountToDebit,
                accountToCredit,
                amountAuthorized,
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
            debitAuthorizeTimestamp shouldBe authorizeTime
        }

      }

      "transition to Pending then immediately to RollingBackDebit after DebitAuthorizeSucceeded (reversal marked)" in {

        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAuth) once
// Note to future self, reversal already requested and there's no point emitting an event
//          stubResultMessenger expects EntryPending(expectedTxnId, expectedDebitResultingBalance) once

          stubAccountMessenger expects (expectedAccountIdToDebit, fullRelease) once
        }

        AuthorizingSpec.givenAuthOnly(this)

        val reverseResult = eventSourcedTestKit.runCommand(Reverse(ackProbe.ref))
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        reverseResult.events.size shouldBe 1
        inside(reverseResult.events.head) { case ReversalRequested(_) =>
        }

        inside(reverseResult.stateOfType[Authorizing]) {
          case Authorizing(
                entryCode,
                entryId,
                accountToDebit,
                accountToCredit,
                amountAuthorized,
                authOnly,
                reversalPending
              ) =>
            accountToDebit shouldBe expectedAccountIdToDebit
            accountToCredit shouldBe expectedAccountIdToCredit
            entryCode shouldBe expectedEntryCode
            entryId shouldBe expectedTxnId
            reversalPending shouldBe true
            authOnly shouldBe true
            amountAuthorized shouldBe expectedEntryAmount
        }

        val acceptAuthResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(debitAuth.hashCode(), expectedAccountIdToDebit, expectedDebitResultingBalance, authorizeTime)
        )

        acceptAuthResult.events.size shouldBe 2
        inside(acceptAuthResult.events.head) {
          case DebitAuthorizeSucceeded(debitedAccountResultingBalance, timestamp) =>
            debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
            timestamp shouldBe authorizeTime
        }
        acceptAuthResult.events(1).getClass shouldBe classOf[ReversalRequested]
        //      inside(acceptAuthResult.stateOfType[Pending]) {
        //        case Pending(
        //        entryCode,
        //        entryId,
        //        accountToDebit,
        //        accountToCredit,
        //        amountAuthorized,
        //        debitedAccountResultingBalance,
        //        debitAuthorizeTimestamp,
        //        reversalPending
        //        ) =>
        //          debitedAccountResultingBalance shouldBe expectedDebitResultingBalance
        //          accountToDebit shouldBe expectedAccountIdToDebit
        //          accountToCredit shouldBe expectedAccountIdToCredit
        //          entryCode shouldBe expectedEntryCode
        //          entryId shouldBe expectedTxnId
        //          reversalPending shouldBe true
        //          amountAuthorized shouldBe expectedEntryAmount
        //          debitAuthorizeTimestamp shouldBe authorizeTime
        //      }

        inside(acceptAuthResult.stateOfType[RollingBackDebit]) {
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

      "transition to Failed after DebitAuthorizeFailed" in {
        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAuth) once

          stubResultMessenger expects EntryFailed(expectedTxnId, LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE) once
        }

        AuthorizingSpec.givenAuthOnly(this)

        val debitResult = eventSourcedTestKit.runCommand(
          RejectAccounting(
            debitAuth.hashCode(),
            expectedAccountIdToDebit,
            LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE
          )
        )
        inside(debitResult.stateOfType[Failed]) { case Failed(entryCode, entryId, code) =>
          code shouldBe LedgerError.CREDIT_ACCOUNT_INSUFFICIENT_AVAILABLE.toString
          entryCode shouldBe expectedEntryCode
          entryId shouldBe expectedTxnId
        }

      }
    }
  }
}
