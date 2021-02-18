package io.openledger.projection.entry

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.persistence.jdbc.query.javadsl.JdbcReadJournal
import akka.persistence.query.Offset
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.jdbc.scaladsl.JdbcProjection
import akka.projection.scaladsl.{ExactlyOnceProjection, SourceProvider}
import akka.projection.{ProjectionBehavior, ProjectionId}
import io.openledger.events.EntryEvent
import io.openledger.projection.PlainJdbcSession

import javax.sql.DataSource

object EntryProjection {
  def apply(dataSource: DataSource)(implicit system: ActorSystem[_]) = new EntryProjection(dataSource)
}

class EntryProjection(
    dataSource: DataSource
)(implicit system: ActorSystem[_]) {

  def sourceProvider(tag: String): SourceProvider[Offset, EventEnvelope[EntryEvent]] =
    EventSourcedProvider
      .eventsByTag[EntryEvent](system, readJournalPluginId = JdbcReadJournal.Identifier, tag = tag)

  def projection(tag: String): ExactlyOnceProjection[Offset, EventEnvelope[EntryEvent]] =
    JdbcProjection
      .exactlyOnce(
        projectionId = ProjectionId("Entry", tag),
        sourceProvider = sourceProvider(tag),
        sessionFactory = () => new PlainJdbcSession(dataSource),
        handler = () => EntryProjectionHandler()
      )

  ShardedDaemonProcess(system).init[ProjectionBehavior.Command](
    name = "entries",
    numberOfInstances = EntryEvent.tagDistribution.tags.size,
    behaviorFactory = (i: Int) => ProjectionBehavior(projection(EntryEvent.tagDistribution.tags(i))),
    stopMessage = ProjectionBehavior.Stop
  )
}
