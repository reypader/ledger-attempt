package io.openledger

import akka.actor.CoordinatedShutdown
import akka.actor.typed._
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.stream.QueueOfferResult
import akka.util.Timeout
import io.openledger.StreamConsumer.StreamIncoming
import io.openledger.domain.account.Account
import io.openledger.domain.account.Account._
import io.openledger.domain.transaction.Transaction
import io.openledger.domain.transaction.Transaction.{apply => _, _}

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success}

//TODO: unexpected TransactionCommand on each state must fail
class Application extends App {
  implicit def asFiniteDuration(d: java.time.Duration): FiniteDuration =
    scala.concurrent.duration.Duration.fromNanos(d.toNanos)

  implicit val system: ActorSystem[SpawnProtocol.Command] = ActorSystem(Behaviors.setup[SpawnProtocol.Command] { context =>
    SpawnProtocol()
  }, "OpenLedger-Processor")
  implicit val scheduler: Scheduler = system.scheduler
  implicit val executionContext: ExecutionContextExecutor = system.executionContext
  implicit val askTimeout: Timeout = 10.seconds //TODO : Config these
  val coordinatedShutdown = CoordinatedShutdown(system)

  val sharding = ClusterSharding(system)

  val AccountTypeKey = EntityTypeKey[Account.AccountCommand]("Account")
  val TransactionTypeKey = EntityTypeKey[Transaction.TransactionCommand]("Transaction")

  val accountShardRegion: ActorRef[ShardingEnvelope[Account.AccountCommand]] =
    sharding.init(Entity(AccountTypeKey)(createBehavior = entityContext => Account(entityContext.entityId)(transactionMessenger, () => DateUtils.now())))
  val transactionShardRegion: ActorRef[ShardingEnvelope[Transaction.TransactionCommand]] =
    sharding.init(Entity(TransactionTypeKey)(createBehavior = entityContext => Transaction(entityContext.entityId)(accountMessenger, resultMessenger)))
  val producerQueue = KafkaProducerSetup(coordinatedShutdown).run()

  def transactionMessenger(transactionId: String, message: AccountingStatus): Unit = message match {
    case AccountingSuccessful(cmdHash, accountId, availableBalance, currentBalance, _, timestamp) =>
      transactionResolver(transactionId) ! AcceptAccounting(cmdHash, accountId, ResultingBalance(availableBalance, currentBalance), timestamp)
    case AccountingFailed(cmdHash, accountId, code) =>
      transactionResolver(transactionId) ! RejectAccounting(cmdHash, accountId, code)
  }

  def transactionResolver(transactionId: String): RecipientRef[TransactionCommand] = sharding.entityRefFor(TransactionTypeKey, transactionId)

  def accountMessenger(accountId: String, message: AccountingCommand): Unit = {
    accountResolver(accountId) ! message
  }

  def accountResolver(accountId: String): RecipientRef[AccountCommand] = sharding.entityRefFor(AccountTypeKey, accountId)

  def resultMessenger(message: TransactionResult): Unit = {
    producerQueue.offer(message).onComplete {
      case Success(value) => value match {
        case QueueOfferResult.Enqueued =>
        //TODO: Log
        case QueueOfferResult.Dropped =>
        //TODO: Log
      }
      case Failure(exception) => //TODO: Log
    }
  }

  for (
    consumerActor <- system.ask((replyTo: ActorRef[ActorRef[StreamIncoming]]) => SpawnProtocol.Spawn(StreamConsumer((id) => sharding.entityRefFor(TransactionTypeKey, id)), name = "StreamConsumer", props = Props.empty, replyTo))
  ) yield KafkaConsumerSetup(coordinatedShutdown, consumerActor).run()

}
