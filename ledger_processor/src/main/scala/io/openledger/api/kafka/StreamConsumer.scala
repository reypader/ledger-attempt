package io.openledger.api.kafka

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, RecipientRef, Scheduler}
import akka.kafka.ConsumerMessage.Committable
import akka.util.Timeout
import io.openledger.LedgerError
import io.openledger.domain.account.Account
import io.openledger.domain.account.Account.{AccountCommand, Ping}
import io.openledger.domain.entry.Entry._
import io.openledger.kafka_operations.EntryRequest.Operation
import org.slf4j.LoggerFactory

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object StreamConsumer {

  val logger = LoggerFactory.getLogger(StreamConsumer.getClass)

  def throttled(
      entryId: String,
      accountToDebit: String,
      accountToCredit: String,
      accountResolver: String => RecipientRef[AccountCommand],
      resultMessenger: ResultMessenger,
      ack: Committable,
      replyTo: RecipientRef[Committable]
  )(
      task: () => Unit
  )(implicit scheduler: Scheduler, executionContext: ExecutionContext): Unit = {
    val d: Future[Account.AccAck] = accountResolver(accountToDebit).ask(Ping)(2.seconds, scheduler)
    val c: Future[Account.AccAck] = accountResolver(accountToCredit).ask(Ping)(2.seconds, scheduler)
    d.flatMap(_ => c).onComplete {
      case Failure(exception) =>
        exception match {
          case _: TimeoutException =>
            logger.error(s"ALERT: Account $accountToDebit or $accountToCredit is being throttled")
            resultMessenger(CommandThrottled(entryId))
            replyTo ! ack
          case ex: Throwable =>
            logger.error(s"ALERT: Failed to contact accounts", ex)
            resultMessenger(CommandRejected(entryId, LedgerError.INTERNAL_ERROR))
            replyTo ! ack
        }
      case Success(_) => task()
    }
  }

  def apply(
      entryResolver: String => RecipientRef[EntryCommand],
      accountResolver: String => RecipientRef[AccountCommand],
      resultMessenger: ResultMessenger
  )(implicit
      askTimeout: Timeout,
      scheduler: Scheduler,
      executionContext: ExecutionContext
  ): Behavior[StreamOp] =
    Behaviors.setup[StreamOp] { implicit context =>
      Behaviors.receiveMessage { case Receive(StreamMessage(operation, offset), replyTo) =>
        context.log.info(s"Received Op message $operation")
        operation match {
          case Operation.Empty =>
            context.log.warn("Received empty request")
            replyTo ! offset

          case Operation.Simple(value) =>
            throttled(
              value.entryId,
              value.accountToDebit,
              value.accountToCredit,
              accountResolver,
              resultMessenger,
              offset,
              replyTo
            ) { () =>
              entryResolver(value.entryId)
                .ask(
                  Begin(value.entryCode, value.accountToDebit, value.accountToCredit, value.amount, _, authOnly = false)
                )
                .onComplete {
                  case Failure(exception) =>
                    context.log.error("Encountered Exception", exception)
                    resultMessenger(CommandRejected(value.entryId, LedgerError.INTERNAL_ERROR))
                    replyTo ! offset
                  case Success(_) =>
                    replyTo ! offset
                }
            }

          case Operation.Authorize(value) =>
            throttled(
              value.entryId,
              value.accountToDebit,
              value.accountToCredit,
              accountResolver,
              resultMessenger,
              offset,
              replyTo
            ) { () =>
              entryResolver(value.entryId)
                .ask(
                  Begin(value.entryCode, value.accountToDebit, value.accountToCredit, value.amount, _, authOnly = true)
                )
                .onComplete {
                  case Failure(exception) =>
                    context.log.error("Encountered Exception", exception)
                    resultMessenger(CommandRejected(value.entryId, LedgerError.INTERNAL_ERROR))
                    replyTo ! offset
                  case Success(_) =>
                    replyTo ! offset
                }
            }

          case Operation.Capture(value) =>
            entryResolver(value.entryId).ask(Capture(value.amountToCapture, _)).onComplete {
              case Failure(exception) =>
                context.log.error("Encountered Exception", exception)
                resultMessenger(CommandRejected(value.entryId, LedgerError.INTERNAL_ERROR))
                replyTo ! offset
              case Success(_) =>
                replyTo ! offset
            }

          case Operation.Reverse(value) =>
            entryResolver(value.entryId).ask(Reverse).onComplete {
              case Failure(exception) =>
                context.log.error("Encountered Exception", exception)
                resultMessenger(CommandRejected(value.entryId, LedgerError.INTERNAL_ERROR))
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
