package io.openledger

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, RecipientRef}
import akka.util.Timeout
import io.openledger.domain.transaction.Transaction._
import io.openledger.kafka_operations.TransactionRequest.Operation

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

object StreamConsumer {

  def apply(transactionResolver: String => RecipientRef[TransactionCommand], resultMessenger: ResultMessenger): Behavior[StreamIncoming] =
    Behaviors.withStash(10) { buffer =>
      Behaviors.setup[StreamIncoming] { context =>
        implicit val askTimeout: Timeout = 60.seconds
        Behaviors.receiveMessage {
          case Op(message, replyTo) =>
            context.log.info(s"Received Op message $message")
            message match {
              case Operation.Empty =>
                context.log.warn("Received empty request")
                replyTo ! StreamAck

              case Operation.Simple(value) =>
                context.ask(transactionResolver(value.transactionId), Begin(value.entryCode, value.accountToDebit, value.accountToCredit, value.amount, _, authOnly = false)) {
                  case Failure(exception) =>
                    context.log.error("Encountered Exception", exception)
                    resultMessenger(CommandRejected(value.transactionId, LedgerError.INTERNAL_ERROR))
                    AckFor(replyTo)
                  case Success(_) =>
                    AckFor(replyTo)
                }

              case Operation.Authorize(value) =>
                context.ask(transactionResolver(value.transactionId), Begin(value.entryCode, value.accountToDebit, value.accountToCredit, value.amount, _, authOnly = true)) {
                  case Failure(exception) =>
                    context.log.error("Encountered Exception", exception)
                    resultMessenger(CommandRejected(value.transactionId, LedgerError.INTERNAL_ERROR))
                    AckFor(replyTo)
                  case Success(_) =>
                    AckFor(replyTo)
                }

              case Operation.Capture(value) =>
                context.ask(transactionResolver(value.transactionId), Capture(value.amountToCapture, _)) {
                  case Failure(exception) =>
                    context.log.error("Encountered Exception", exception)
                    resultMessenger(CommandRejected(value.transactionId, LedgerError.INTERNAL_ERROR))
                    AckFor(replyTo)
                  case Success(_) =>
                    AckFor(replyTo)
                }

              case Operation.Reverse(value) =>
                context.ask(transactionResolver(value.transactionId), Reverse) {
                  case Failure(exception) =>
                    context.log.error("Encountered Exception", exception)
                    resultMessenger(CommandRejected(value.transactionId, LedgerError.INTERNAL_ERROR))
                    AckFor(replyTo)
                  case Success(_) =>
                    AckFor(replyTo)
                }

              case Operation.Resume(value) =>
                context.ask(transactionResolver(value.transactionId), Resume) {
                  case Failure(exception) =>
                    context.log.error("Encountered Exception", exception)
                    resultMessenger(CommandRejected(value.transactionId, LedgerError.INTERNAL_ERROR))
                    AckFor(replyTo)
                  case Success(_) =>
                    AckFor(replyTo)
                }
            }
            Behaviors.same

          case StreamInitialized(replyTo) =>
            context.log.info("Stream Ready")
            replyTo ! StreamAck
            Behaviors.same

          case AckFor(replyTo) =>
            context.log.info("Acking stream")
            replyTo ! StreamAck
            Behaviors.same

          case StreamCompleted =>
            context.log.info("Stream closed")
            buffer.unstashAll(shuttingDown)

          case StreamFailure(ex) =>
            context.log.error("Stream Failure", ex)
            Behaviors.same

          case message: PrepareForShutdown =>
            context.log.info("Preparing for shutdown")
            buffer.stash(message)
            Behaviors.same

        }

      }
    }

  private def shuttingDown: Behavior[StreamIncoming] = Behaviors.setup[StreamIncoming] {
    context =>
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

  sealed trait StreamAck

  sealed trait StreamIncoming

  final case class Op(operation: Operation, replyTo: ActorRef[StreamAck]) extends StreamIncoming

  final case class AckFor(replyTo: ActorRef[StreamAck]) extends StreamIncoming

  final case class StreamInitialized(replyTo: ActorRef[StreamAck]) extends StreamIncoming

  final case class PrepareForShutdown(replyTo: ActorRef[Done]) extends StreamIncoming

  final case class StreamFailure(ex: Throwable) extends StreamIncoming

  final case object StreamAck extends StreamAck

  final case object StreamCompleted extends StreamIncoming

}
