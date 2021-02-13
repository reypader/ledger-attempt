package io.openledger.api.kafka

import akka.Done
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, RecipientRef, Scheduler}
import akka.kafka.ConsumerMessage.Committable
import akka.util.Timeout
import io.openledger.LedgerError
import io.openledger.domain.account.Account
import io.openledger.domain.account.Account.{AccountCommand, Ping}
import io.openledger.domain.entry.Entry._
import io.openledger.domain.entry.states.PairedEntry
import io.openledger.kafka_operations.EntryRequest.Operation
import org.slf4j.LoggerFactory

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object StreamConsumer {

  private val logger = LoggerFactory.getLogger(StreamConsumer.getClass)

  private def throttle(entryRef: RecipientRef[EntryCommand], accountResolver: String => RecipientRef[AccountCommand])(
      implicit
      scheduler: Scheduler,
      executionContext: ExecutionContext
  ): Future[Done] = {
    entryRef
      .ask(Get)(1.second, scheduler)
      .flatMap({
        case entry: PairedEntry =>
          throttle(accountResolver(entry.accountToDebit), accountResolver(entry.accountToCredit))
        case _ => Future(Done.done())
      })
  }

  private def throttle(
      accountToDebit: RecipientRef[AccountCommand],
      accountToCredit: RecipientRef[AccountCommand]
  )(implicit scheduler: Scheduler, executionContext: ExecutionContext): Future[Done] = {
    val d: Future[Account.AccAck] = accountToDebit.ask(Ping)(1.second, scheduler)
    val c: Future[Account.AccAck] = accountToCredit.ask(Ping)(1.second, scheduler)
    d.flatMap(_ => c).map(_ => Done.done())
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
          case op: Operation =>
            val f: (String, Future[TxnAck]) = op match {
              case Operation.Simple(value) =>
                (
                  value.entryId,
                  throttle(accountResolver(value.accountToDebit), accountResolver(value.accountToCredit)).flatMap(_ =>
                    entryResolver(value.entryId).ask(
                      Begin(
                        value.entryCode,
                        value.accountToDebit,
                        value.accountToCredit,
                        value.amount,
                        _,
                        authOnly = false
                      )
                    )
                  )
                )

              case Operation.Authorize(value) =>
                (
                  value.entryId,
                  throttle(accountResolver(value.accountToDebit), accountResolver(value.accountToCredit)).flatMap(_ =>
                    entryResolver(value.entryId).ask(
                      Begin(
                        value.entryCode,
                        value.accountToDebit,
                        value.accountToCredit,
                        value.amount,
                        _,
                        authOnly = true
                      )
                    )
                  )
                )

              case Operation.Capture(value) =>
                val entryRef = entryResolver(value.entryId)
                (
                  value.entryId,
                  throttle(entryRef, accountResolver).flatMap(_ => entryRef.ask(Capture(value.amountToCapture, _)))
                )

              case Operation.Reverse(value) =>
                val entryRef = entryResolver(value.entryId)
                (
                  value.entryId,
                  throttle(entryRef, accountResolver).flatMap(_ => entryRef.ask(Reverse))
                )
            }
            f._2.onComplete {
              case Failure(exception) =>
                exception match {
                  case _: TimeoutException =>
                    logger.error(s"ALERT: Accounts are being throttled")
                    resultMessenger(CommandThrottled(f._1))
                    replyTo ! offset
                  case ex: Throwable =>
                    logger.error("Encountered Exception", ex)
                    resultMessenger(CommandRejected(f._1, LedgerError.INTERNAL_ERROR))
                    replyTo ! offset
                }
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
