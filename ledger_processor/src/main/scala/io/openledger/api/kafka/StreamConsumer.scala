package io.openledger.api.kafka

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, RecipientRef, Scheduler}
import akka.kafka.ConsumerMessage.Committable
import akka.util.Timeout
import io.openledger.LedgerError
import io.openledger.domain.transaction.Transaction._
import io.openledger.kafka_operations.TransactionRequest.Operation

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object StreamConsumer {

  def apply(transactionResolver: String => RecipientRef[TransactionCommand], resultMessenger: ResultMessenger)(implicit askTimeout: Timeout, scheduler: Scheduler, executionContext: ExecutionContext): Behavior[StreamOp] =
    Behaviors.setup[StreamOp] { context =>
      Behaviors.receiveMessage {
        case Receive(StreamMessage(operation, offset), replyTo) =>
          context.log.info(s"Received Op message $operation")
          operation match {
            case Operation.Empty =>
              context.log.warn("Received empty request")
              replyTo ! offset

            case Operation.Simple(value) =>
              transactionResolver(value.transactionId).ask(Begin(value.entryCode, value.accountToDebit, value.accountToCredit, value.amount, _, authOnly = false)).onComplete {
                case Failure(exception) =>
                  context.log.error("Encountered Exception", exception)
                  resultMessenger(CommandRejected(value.transactionId, LedgerError.INTERNAL_ERROR))
                  replyTo ! offset
                case Success(_) =>
                  replyTo ! offset
              }

            case Operation.Authorize(value) =>
              transactionResolver(value.transactionId).ask(Begin(value.entryCode, value.accountToDebit, value.accountToCredit, value.amount, _, authOnly = true)).onComplete {
                case Failure(exception) =>
                  context.log.error("Encountered Exception", exception)
                  resultMessenger(CommandRejected(value.transactionId, LedgerError.INTERNAL_ERROR))
                  replyTo ! offset
                case Success(_) =>
                  replyTo ! offset
              }

            case Operation.Capture(value) =>
              transactionResolver(value.transactionId).ask(Capture(value.amountToCapture, _)).onComplete {
                case Failure(exception) =>
                  context.log.error("Encountered Exception", exception)
                  resultMessenger(CommandRejected(value.transactionId, LedgerError.INTERNAL_ERROR))
                  replyTo ! offset
                case Success(_) =>
                  replyTo ! offset
              }

            case Operation.Reverse(value) =>
              transactionResolver(value.transactionId).ask(Reverse).onComplete {
                case Failure(exception) =>
                  context.log.error("Encountered Exception", exception)
                  resultMessenger(CommandRejected(value.transactionId, LedgerError.INTERNAL_ERROR))
                  replyTo ! offset
                case Success(_) =>
                  replyTo ! offset
              }

            case Operation.Resume(value) =>
              transactionResolver(value.transactionId).ask(Resume).onComplete {
                case Failure(exception) =>
                  context.log.error("Encountered Exception", exception)
                  resultMessenger(CommandRejected(value.transactionId, LedgerError.INTERNAL_ERROR))
                  replyTo ! offset
                case Success(_) =>
                  replyTo ! offset
              }
          }
          Behaviors.same


      }

    }


  sealed trait StreamOp

  final case class Receive(message: StreamMessage, replyTo: ActorRef[Committable]) extends StreamOp

  final case class StreamMessage(operation: Operation, committable: Committable)

}
