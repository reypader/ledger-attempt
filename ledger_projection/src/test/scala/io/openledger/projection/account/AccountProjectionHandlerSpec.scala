package io.openledger.projection.account

import akka.persistence.query.NoOffset
import akka.projection.eventsourced.EventEnvelope
import io.openledger.AccountingMode.{CREDIT, DEBIT}
import io.openledger.events._
import io.openledger.projection.account.AccountInfoRepository.AccountInfo
import io.openledger.projection.account.AccountOverdraftRepository.{Overdraft, OverdraftType}
import io.openledger.projection.account.AccountStatementRepository.{AvailableMovement, FullMovement, MovementType}
import io.openledger.projection.{PlainJdbcSession, UnnecessaryDatasourceStub}
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, TestSuite}

import java.sql.Connection
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

class AccountProjectionHandlerSpec
    extends TestSuite
    with Matchers
    with BeforeAndAfterAll
    with ScalaFutures
    with AnyWordSpecLike
    with BeforeAndAfterEach
    with MockFactory {

  private val mockStatementRepo = mock[AccountStatementRepository]
  private val mockInfoRepo = mock[AccountInfoRepository]
  private val mockOverdraftRepo = mock[AccountOverdraftRepository]
  private val underTest = AccountProjectionHandler(mockInfoRepo, mockStatementRepo, mockOverdraftRepo)

  private val mockConnection = mock[Connection]
  private val jdbcSession = new PlainJdbcSession(new UnnecessaryDatasourceStub(mockConnection))

  def envelope(event: AccountEvent): EventEnvelope[AccountEvent] = EventEnvelope(NoOffset, "Account|acc", 1L, event, 1L)

  private val now: OffsetDateTime = OffsetDateTime.now()
  "AccountProjection" when {
    "processing openings" must {
      def given(): Unit = {
        (mockInfoRepo.get(_: String)(_: Connection)).expects(*, *).never()
        (mockStatementRepo.save(_: FullMovement)(_: Connection)).expects(*, *).never()
        (mockStatementRepo.save(_: AvailableMovement)(_: Connection)).expects(*, *).never()
        (mockOverdraftRepo.save(_: Overdraft)(_: Connection)).expects(*, *).never()
      }
      "persist Debit Account info" in {
        given()

        (mockInfoRepo
          .save(_: AccountInfo)(_: Connection))
          .expects(AccountInfo("acc", DEBIT, Set("A", "B"), now), mockConnection)
          .once()

        val event = DebitAccountOpened(now, Set("A", "B"))

        underTest.process(jdbcSession, envelope(event))
      }
      "persist Credit Account info" in {
        given()

        (mockInfoRepo
          .save(_: AccountInfo)(_: Connection))
          .expects(AccountInfo("acc", CREDIT, Set("A", "B"), now), mockConnection)
          .once()

        val event = CreditAccountOpened(now, Set("A", "B"))

        underTest.process(jdbcSession, envelope(event))
      }
    }
    "processing overdrafts" must {
      def given(): Unit = {
        (mockInfoRepo.get(_: String)(_: Connection)).expects(*, *).never()
        (mockStatementRepo.save(_: FullMovement)(_: Connection)).expects(*, *).never()
        (mockStatementRepo.save(_: AvailableMovement)(_: Connection)).expects(*, *).never()
      }

      "persist Overpaid" in {
        given()

        (mockOverdraftRepo
          .save(_: Overdraft)(_: Connection))
          .expects(Overdraft("eid", "ec", OverdraftType.OVERPAID, "acc", now), mockConnection)
          .once()

        val event = Overpaid("eid", "ec", now)

        underTest.process(jdbcSession, envelope(event))
      }

      "persist Overdrawn" in {
        given()

        (mockOverdraftRepo
          .save(_: Overdraft)(_: Connection))
          .expects(Overdraft("eid", "ec", OverdraftType.OVERDRAWN, "acc", now), mockConnection)
          .once()

        val event = Overdrawn("eid", "ec", now)

        underTest.process(jdbcSession, envelope(event))
      }
    }
    "processing a DEBIT account" must {
      def given(): Unit = {
        (mockOverdraftRepo.save(_: Overdraft)(_: Connection)).expects(*, *).never()
        (mockInfoRepo
          .get(_: String)(_: Connection))
          .expects("acc", mockConnection)
          .returning(
            AccountInfo("acc", DEBIT, Set.empty, OffsetDateTime.now())
          )
          .once()
      }
      "persist positive for Debited" in {
        given()

        (mockStatementRepo
          .save(_: FullMovement)(_: Connection))
          .expects(
            FullMovement("acc", "eid", "ec", MovementType.DEBIT, 1, 1, 2, 3, now, now),
            (mockConnection)
          )
          .once()

        val event = Debited("eid", "ec", 1, 2, 3, now)

        underTest.process(jdbcSession, envelope(event))

      }
      "persist positive for DebitAuthorized" in {
        given()

        (mockStatementRepo
          .save(_: AvailableMovement)(_: Connection))
          .expects(
            AvailableMovement("acc", "eid", "ec", MovementType.DEBIT_AUTHORIZE, 1, 2, now),
            (mockConnection)
          )
          .once()

        val event = DebitAuthorized("eid", "ec", 1, 2, 10, now)

        underTest.process(jdbcSession, envelope(event))
      }
      "persist mixed sign for DebitCaptured" in {
        given()

        val backThen = now.minus(1, ChronoUnit.DAYS)

        (mockStatementRepo
          .save(_: FullMovement)(_: Connection))
          .expects(
            FullMovement("acc", "eid", "ec", MovementType.DEBIT_CAPTURE, -5, 1, 2, 3, backThen, now),
            (mockConnection)
          )
          .once()

        val event = DebitCaptured("eid", "ec", 1, 5, 2, 3, 10, backThen, now)

        underTest.process(jdbcSession, envelope(event))
      }
      "persist negative for DebitReleased" in {
        given()

        (mockStatementRepo
          .save(_: AvailableMovement)(_: Connection))
          .expects(
            AvailableMovement("acc", "eid", "ec", MovementType.DEBIT_RELEASE, -8, 2, now),
            (mockConnection)
          )
          .once()

        val event = DebitReleased("eid", "ec", 8, 2, 10, now)

        underTest.process(jdbcSession, envelope(event))
      }
      "persist negative for Credited" in {
        given()

        (mockStatementRepo
          .save(_: FullMovement)(_: Connection))
          .expects(
            FullMovement("acc", "eid", "ec", MovementType.CREDIT, -1, -1, 2, 3, now, now),
            (mockConnection)
          )
          .once()

        val event = Credited("eid", "ec", 1, 2, 3, now)

        underTest.process(jdbcSession, envelope(event))
      }
    }

    "processing a CREDIT account" must {
      def given(): Unit = {
        (mockInfoRepo
          .get(_: String)(_: Connection))
          .expects("acc", mockConnection)
          .returning(
            AccountInfo("acc", CREDIT, Set.empty, OffsetDateTime.now())
          )
          .once()
      }
      "persist positive for Debited" in {
        given()

        (mockStatementRepo
          .save(_: FullMovement)(_: Connection))
          .expects(
            FullMovement("acc", "eid", "ec", MovementType.DEBIT, -1, -1, 2, 3, now, now),
            (mockConnection)
          )
          .once()

        val event = Debited("eid", "ec", 1, 2, 3, now)

        underTest.process(jdbcSession, envelope(event))

      }
      "persist positive for DebitAuthorized" in {
        given()

        (mockStatementRepo
          .save(_: AvailableMovement)(_: Connection))
          .expects(
            AvailableMovement("acc", "eid", "ec", MovementType.DEBIT_AUTHORIZE, -1, 2, now),
            (mockConnection)
          )
          .once()

        val event = DebitAuthorized("eid", "ec", 1, 2, 10, now)

        underTest.process(jdbcSession, envelope(event))
      }
      "persist mixed sign for DebitCaptured" in {
        given()

        val backThen = now.minus(1, ChronoUnit.DAYS)

        (mockStatementRepo
          .save(_: FullMovement)(_: Connection))
          .expects(
            FullMovement("acc", "eid", "ec", MovementType.DEBIT_CAPTURE, 5, -1, 2, 3, backThen, now),
            (mockConnection)
          )
          .once()

        val event = DebitCaptured("eid", "ec", 1, 5, 2, 3, 10, backThen, now)

        underTest.process(jdbcSession, envelope(event))
      }
      "persist negative for DebitReleased" in {
        given()

        (mockStatementRepo
          .save(_: AvailableMovement)(_: Connection))
          .expects(
            AvailableMovement("acc", "eid", "ec", MovementType.DEBIT_RELEASE, 8, 2, now),
            (mockConnection)
          )
          .once()

        val event = DebitReleased("eid", "ec", 8, 2, 10, now)

        underTest.process(jdbcSession, envelope(event))
      }
      "persist negative for Credited" in {
        given()

        (mockStatementRepo
          .save(_: FullMovement)(_: Connection))
          .expects(
            FullMovement("acc", "eid", "ec", MovementType.CREDIT, 1, 1, 2, 3, now, now),
            (mockConnection)
          )
          .once()

        val event = Credited("eid", "ec", 1, 2, 3, now)

        underTest.process(jdbcSession, envelope(event))
      }
    }
  }

}
