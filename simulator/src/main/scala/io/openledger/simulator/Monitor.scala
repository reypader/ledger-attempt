package io.openledger.simulator

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import io.openledger.kafka_operations.EntryResult

import java.util.UUID
import scala.concurrent.Promise

object Monitor {

  def apply(
      handle: Promise[Map[String, Int]],
      watched: Set[String] = Set.empty,
      latencies: Map[String, Seq[Long]] = Map.empty
  ): Behavior[MonitorOperation] =
    Behaviors.setup { context =>
      Behaviors
        .receiveMessage[MonitorOperation] {
          case SpawnCases(cases) =>
            var w = watched
            cases.foreach(b => {
              val id = UUID.randomUUID().toString
              val ref = context.spawn(b, id)
              context.watchWith(ref, Stopped(id))
              w += id
            })
            apply(handle, w, latencies)
          case Done(operation, latency) =>
            var l = latencies
            if (latencies.contains(operation)) {
              var x = latencies(operation)
              x = x :+ latency
              l = l + (operation -> x)
            } else {
              l = l + (operation -> Seq(latency))
            }
            apply(handle, watched, l)
          case Stopped(id) => {
            val newWatched = watched - id
            if (newWatched.nonEmpty) {
              apply(handle, newWatched, latencies)
            } else {
              var counts = Map.empty[String, Int]
              latencies.foreach { l =>
                context.log.info(s"Average Latency: ${l._1} -- ${l._2.sum / l._2.size} milliseconds")
                counts = counts + (l._1 -> l._2.size)
              }
              handle.success(counts)
              Behaviors.stopped
            }
          }
        }

    }

  trait MonitorOperation
  case class SpawnCases(cases: Seq[Behavior[EntryResult]]) extends MonitorOperation
  case class Done(operation: String, latency: Long) extends MonitorOperation
  case class Stopped(id: String) extends MonitorOperation
}
