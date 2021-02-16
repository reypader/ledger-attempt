package io.openledger.domain.entry

import io.openledger.AccountingMode
import io.openledger.domain.entry.Entry.{Ack, Adjust, Begin, Get, TxnAck}
import io.openledger.domain.entry.states.{Adjusting, Authorizing, EntryState, Ready}
import io.openledger.events.{AdjustRequested, Started}
import org.scalatest.Inside.inside

import scala.language.postfixOps

object ReadySpec {
  def given(spec: AbstractEntrySpecBase): EntryState = {
    val initial = spec.eventSourcedTestKit.runCommand(Get)
    initial.stateOfType[Ready]
  }
}

class ReadySpec extends AbstractEntrySpecBase {
  "An Entry" when {

    "Ready Entry" must {
      "transition to Authorizing after Started" in {
        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAuth) once
        }
        val beginResult = eventSourcedTestKit.runCommand(
          Begin(
            expectedEntryCode,
            expectedAccountIdToDebit,
            expectedAccountIdToCredit,
            expectedEntryAmount,
            ackProbe.ref
          )
        )
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        beginResult.events.size shouldBe 1
        inside(beginResult.events.head) {
          case Started(entryCode, accountToDebit, accountToCredit, amount, authOnly, _) =>
            entryCode shouldBe expectedEntryCode
            accountToDebit shouldBe expectedAccountIdToDebit
            accountToCredit shouldBe expectedAccountIdToCredit
            amount shouldBe expectedEntryAmount
            authOnly shouldBe false
        }
        inside(beginResult.stateOfType[Authorizing]) {
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
            reversalPending shouldBe false
            authOnly shouldBe false
            amountAuthorized shouldBe expectedEntryAmount
        }

      }

      "transition to Adjusting after AdjustRequested (debit)" in {
        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAdjust) once
        }
        val beginResult = eventSourcedTestKit.runCommand(
          Adjust(expectedEntryCode, expectedAccountIdToDebit, expectedEntryAmount, AccountingMode.DEBIT, ackProbe.ref)
        )
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        beginResult.events shouldBe Seq(
          AdjustRequested(expectedEntryCode, expectedAccountIdToDebit, expectedEntryAmount, AccountingMode.DEBIT)
        )

        inside(beginResult.stateOfType[Adjusting]) {
          case Adjusting(entryCode, entryId, accountToAdjust, amount, mode) =>
            accountToAdjust shouldBe expectedAccountIdToDebit
            mode shouldBe AccountingMode.DEBIT
            entryCode shouldBe expectedEntryCode
            entryId shouldBe expectedTxnId
            amount shouldBe expectedEntryAmount
        }
      }

      "transition to Adjusting after AdjustRequested (credit)" in {
        inSequence {
          stubAccountMessenger expects (expectedAccountIdToCredit, creditAdjust) once
        }
        val beginResult = eventSourcedTestKit.runCommand(
          Adjust(expectedEntryCode, expectedAccountIdToCredit, expectedEntryAmount, AccountingMode.CREDIT, ackProbe.ref)
        )
        ackProbe.expectMessageType[TxnAck] shouldBe Ack
        beginResult.events shouldBe Seq(
          AdjustRequested(expectedEntryCode, expectedAccountIdToCredit, expectedEntryAmount, AccountingMode.CREDIT)
        )
        inside(beginResult.stateOfType[Adjusting]) {
          case Adjusting(entryCode, entryId, accountToAdjust, amount, mode) =>
            accountToAdjust shouldBe expectedAccountIdToCredit
            mode shouldBe AccountingMode.CREDIT
            entryCode shouldBe expectedEntryCode
            entryId shouldBe expectedTxnId
            amount shouldBe expectedEntryAmount
        }
      }
    }

  }
}
