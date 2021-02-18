package io.openledger.projection.account

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.persistence.jdbc.query.javadsl.JdbcReadJournal
import akka.persistence.query.Offset
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.jdbc.scaladsl.JdbcProjection
import akka.projection.scaladsl.{ExactlyOnceProjection, SourceProvider}
import akka.projection.{ProjectionBehavior, ProjectionId}
import io.openledger.events.AccountEvent
import io.openledger.projection.PlainJdbcSession

import javax.sql.DataSource

object AccountProjection {
  def apply(
      dataSource: DataSource,
      accountInfoRepository: AccountInfoRepository,
      accountStatementRepository: AccountStatementRepository,
      accountOverdraftRepository: AccountOverdraftRepository
  )(implicit system: ActorSystem[_]) =
    new AccountProjection(dataSource, accountInfoRepository, accountStatementRepository, accountOverdraftRepository)
}

class AccountProjection(
    dataSource: DataSource,
    accountInfoRepository: AccountInfoRepository,
    accountStatementRepository: AccountStatementRepository,
    accountOverdraftRepository: AccountOverdraftRepository
)(implicit system: ActorSystem[_]) {

  def sourceProvider(tag: String): SourceProvider[Offset, EventEnvelope[AccountEvent]] =
    EventSourcedProvider
      .eventsByTag[AccountEvent](system, readJournalPluginId = JdbcReadJournal.Identifier, tag = tag)

  def projection(tag: String): ExactlyOnceProjection[Offset, EventEnvelope[AccountEvent]] =
    JdbcProjection
      .exactlyOnce(
        projectionId = ProjectionId("Account", tag),
        sourceProvider = sourceProvider(tag),
        sessionFactory = () => new PlainJdbcSession(dataSource),
        handler =
          () => AccountProjectionHandler(accountInfoRepository, accountStatementRepository, accountOverdraftRepository)
      )

  ShardedDaemonProcess(system).init[ProjectionBehavior.Command](
    name = "accounts",
    numberOfInstances = AccountEvent.tagDistribution.tags.size,
    behaviorFactory = (i: Int) => ProjectionBehavior(projection(AccountEvent.tagDistribution.tags(i))),
    stopMessage = ProjectionBehavior.Stop
  )
}
