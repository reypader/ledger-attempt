package io.openledger

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, RecipientRef}
import io.openledger.domain.transaction.Transaction.{Ack, TransactionCommand, TxnAck}

object StreamConsumer {
  def apply(transactionResolver: String => RecipientRef[TransactionCommand]): Behavior[StreamIncoming] =
    Behaviors.withStash(10) { buffer =>
      Behaviors.setup[StreamIncoming] { context =>
        Behaviors.receiveMessage {
          case Op(target, message) =>
            transactionResolver(target) ! message
            Behaviors.same

          case StreamInitialized(replyTo) =>
            context.log.info("Stream Ready")
            replyTo ! Ack
            Behaviors.same

          case NoOp(replyTo) =>
            context.log.warn("Received empty request")
            replyTo ! Ack
            Behaviors.same

          case StreamCompleted =>
            context.log.info("Stream closed")
            buffer.unstashAll(shuttingDown)

          case StreamFailure(ex) =>
            context.log.error("Stream Failure", ex)
            Behaviors.same

          case message: PrepareForShutdown =>
            context.log.error("Preparing for shutdown")
            buffer.stash(message)
            Behaviors.same

        }

      }
    }

  private def shuttingDown: Behavior[StreamIncoming] = Behaviors.setup[StreamIncoming] { context =>
    Behaviors.receiveMessage {
      case PrepareForShutdown(replyTo) =>
        context.log.info("Stream drained. Stream Consumer stopping...")
        replyTo ! Done
        Behaviors.stopped
      case _ =>
        context.log.warn("Received unexpected message while shutting down")
        Behaviors.same

    }
  }

  sealed trait StreamIncoming

  final case class Op(targetTransaction: String, operation: TransactionCommand) extends StreamIncoming

  final case class NoOp(replyTo: ActorRef[TxnAck]) extends StreamIncoming

  final case class StreamInitialized(replyTo: ActorRef[TxnAck]) extends StreamIncoming

  final case class PrepareForShutdown(replyTo: ActorRef[Done]) extends StreamIncoming

  final case class StreamFailure(ex: Throwable) extends StreamIncoming

  final case object StreamCompleted extends StreamIncoming

}
