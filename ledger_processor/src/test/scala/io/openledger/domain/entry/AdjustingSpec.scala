package io.openledger.domain.entry

import io.openledger.{AccountingMode, DateUtils, LedgerError, ResultingBalance}
import io.openledger.domain.entry.Entry.{
  AcceptAccounting,
  Ack,
  Adjust,
  AdjustmentSuccessful,
  Begin,
  EntryFailed,
  RejectAccounting,
  TxnAck
}
import io.openledger.domain.entry.states.{Adjusted, Adjusting, Authorizing, EntryState, Failed}
import io.openledger.events.{
  AdjustRequested,
  CreditAdjustmentDone,
  CreditAdjustmentFailed,
  DebitAdjustmentDone,
  DebitAdjustmentFailed,
  Started
}
import org.scalatest.Inside.inside

import scala.language.postfixOps

object AdjustingSpec {
  def givenCreditAdjusting(spec: AbstractEntrySpecBase): EntryState = {
    ReadySpec.given(spec)
    val beginResult = spec.eventSourcedTestKit.runCommand(
      Adjust(
        spec.expectedEntryCode,
        spec.expectedAccountIdToCredit,
        spec.expectedEntryAmount,
        AccountingMode.CREDIT,
        spec.ackProbe.ref
      )
    )
    spec.ackProbe.expectMessageType[TxnAck]
    beginResult.stateOfType[Adjusting]
  }
  def givenDebitAdjusting(spec: AbstractEntrySpecBase): EntryState = {
    ReadySpec.given(spec)
    val beginResult = spec.eventSourcedTestKit.runCommand(
      Adjust(
        spec.expectedEntryCode,
        spec.expectedAccountIdToDebit,
        spec.expectedEntryAmount,
        AccountingMode.DEBIT,
        spec.ackProbe.ref
      )
    )
    spec.ackProbe.expectMessageType[TxnAck]
    beginResult.stateOfType[Adjusting]
  }
}

class AdjustingSpec extends AbstractEntrySpecBase {

  "An Entry" when {
    "Adjusting (credit) Entry" must {

      "transition to Adjusted on CreditAdjustmentDone" in {
        val expectedAdjustmentBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))

        inSequence {
          stubAccountMessenger expects (expectedAccountIdToCredit, creditAdjust) once

          stubResultMessenger expects AdjustmentSuccessful(
            expectedTxnId,
            AccountingMode.CREDIT,
            expectedAdjustmentBalance
          ) once
        }

        AdjustingSpec.givenCreditAdjusting(this)

        val adjustResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(
            creditAdjust.hashCode(),
            expectedAccountIdToCredit,
            expectedAdjustmentBalance,
            DateUtils.now()
          )
        )
        adjustResult.events.size shouldBe 2
        inside(adjustResult.events.head) { case CreditAdjustmentDone(debitedAccountResultingBalance) =>
          debitedAccountResultingBalance shouldBe expectedAdjustmentBalance
        }
        adjustResult.events(1).getClass shouldBe classOf[io.openledger.events.Done]

        inside(adjustResult.stateOfType[Adjusted]) {
          case Adjusted(entryCode, entryId, accountToAdjust, amount, mode, resultingBalance) =>
            accountToAdjust shouldBe expectedAccountIdToCredit
            entryCode shouldBe expectedEntryCode
            entryId shouldBe expectedTxnId
            amount shouldBe expectedEntryAmount
            mode shouldBe AccountingMode.CREDIT
            resultingBalance shouldBe expectedAdjustmentBalance
        }

      }

      "transition to Failed on CreditAdjustmentFailed" in {
        inSequence {
          stubAccountMessenger expects (expectedAccountIdToCredit, creditAdjust) once

          stubResultMessenger expects (EntryFailed(
            expectedTxnId,
            LedgerError.UNSUPPORTED_ACCOUNT_OPERATION_ON_CURRENT_STATE
          )) once
        }

        AdjustingSpec.givenCreditAdjusting(this)

        val adjustResult = eventSourcedTestKit.runCommand(
          RejectAccounting(
            creditAdjust.hashCode(),
            expectedAccountIdToCredit,
            LedgerError.UNSUPPORTED_ACCOUNT_OPERATION_ON_CURRENT_STATE
          )
        )

        adjustResult.events.size shouldBe 2
        inside(adjustResult.events.head) { case CreditAdjustmentFailed(code) =>
          code shouldBe LedgerError.UNSUPPORTED_ACCOUNT_OPERATION_ON_CURRENT_STATE.toString
        }
        adjustResult.events(1).getClass shouldBe classOf[io.openledger.events.Done]

        inside(adjustResult.stateOfType[Failed]) { case Failed(entryCode, entryId, code) =>
          entryCode shouldBe expectedEntryCode
          entryId shouldBe expectedTxnId
          code shouldBe LedgerError.UNSUPPORTED_ACCOUNT_OPERATION_ON_CURRENT_STATE.toString
        }

      }
    }

    "Adjusting (debit) Entry" must {

      "transition to Adjusted on DebitAdjustmentDone" in {
        val expectedAdjustmentBalance: ResultingBalance = ResultingBalance(BigDecimal(1), BigDecimal(2))

        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAdjust) once

          stubResultMessenger expects AdjustmentSuccessful(
            expectedTxnId,
            AccountingMode.DEBIT,
            expectedAdjustmentBalance
          ) once
        }

        AdjustingSpec.givenDebitAdjusting(this)

        val adjustResult = eventSourcedTestKit.runCommand(
          AcceptAccounting(debitAdjust.hashCode(), expectedAccountIdToDebit, expectedAdjustmentBalance, DateUtils.now())
        )
        adjustResult.events.size shouldBe 2
        inside(adjustResult.events.head) { case DebitAdjustmentDone(debitedAccountResultingBalance) =>
          debitedAccountResultingBalance shouldBe expectedAdjustmentBalance
        }
        adjustResult.events(1).getClass shouldBe classOf[io.openledger.events.Done]

        inside(adjustResult.stateOfType[Adjusted]) {
          case Adjusted(entryCode, entryId, accountToAdjust, amount, mode, resultingBalance) =>
            accountToAdjust shouldBe expectedAccountIdToDebit
            entryCode shouldBe expectedEntryCode
            entryId shouldBe expectedTxnId
            amount shouldBe expectedEntryAmount
            mode shouldBe AccountingMode.DEBIT
            resultingBalance shouldBe expectedAdjustmentBalance
        }

      }

      "transition to Failed on DebitAdjustmentFailed" in {
        inSequence {
          stubAccountMessenger expects (expectedAccountIdToDebit, debitAdjust) once

          stubResultMessenger expects (EntryFailed(
            expectedTxnId,
            LedgerError.UNSUPPORTED_ACCOUNT_OPERATION_ON_CURRENT_STATE
          )) once
        }

        AdjustingSpec.givenDebitAdjusting(this)

        val adjustResult = eventSourcedTestKit.runCommand(
          RejectAccounting(
            debitAdjust.hashCode(),
            expectedAccountIdToDebit,
            LedgerError.UNSUPPORTED_ACCOUNT_OPERATION_ON_CURRENT_STATE
          )
        )

        adjustResult.events.size shouldBe 2
        inside(adjustResult.events.head) { case DebitAdjustmentFailed(code) =>
          code shouldBe LedgerError.UNSUPPORTED_ACCOUNT_OPERATION_ON_CURRENT_STATE.toString
        }
        adjustResult.events(1).getClass shouldBe classOf[io.openledger.events.Done]

        inside(adjustResult.stateOfType[Failed]) { case Failed(entryCode, entryId, code) =>
          entryCode shouldBe expectedEntryCode
          entryId shouldBe expectedTxnId
          code shouldBe LedgerError.UNSUPPORTED_ACCOUNT_OPERATION_ON_CURRENT_STATE.toString
        }
      }
    }
  }
}
