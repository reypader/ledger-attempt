package io.openledger

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.typed._
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.kafka.scaladsl.Consumer
import akka.kafka.{CommitterSettings, ConsumerSettings, Subscriptions}
import akka.stream._
import akka.stream.scaladsl.Keep
import akka.stream.typed.scaladsl.ActorSink
import akka.util.Timeout
import io.openledger.StreamConsumer._
import io.openledger.domain.account.Account
import io.openledger.domain.account.Account.{AccountingCommand, AccountingFailed, AccountingStatus, AccountingSuccessful}
import io.openledger.domain.transaction.Transaction
import io.openledger.domain.transaction.Transaction.{apply => _, _}
import io.openledger.operations.TransactionRequest
import io.openledger.operations.TransactionRequest.Operation
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, StringDeserializer}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

class Application extends App {
  implicit def asFiniteDuration(d: java.time.Duration): FiniteDuration =
    scala.concurrent.duration.Duration.fromNanos(d.toNanos)

  //  val conf = ConfigFactory.defaultApplication()
  implicit val system: ActorSystem[SpawnProtocol.Command] = ActorSystem(Behaviors.setup[SpawnProtocol.Command] { context =>
    SpawnProtocol()
  }, "OpenLedger-Processor")
  implicit val scheduler: Scheduler = system.scheduler
  implicit val executionContext: ExecutionContextExecutor = system.executionContext
  implicit val askTimeout: Timeout = 10.seconds
  val coordinatedShutdown = CoordinatedShutdown(system)

  val sharding = ClusterSharding(system)

  val AccountTypeKey = EntityTypeKey[Account.AccountCommand]("Account")
  val TransactionTypeKey = EntityTypeKey[Transaction.TransactionCommand]("Transaction")

  val accountShardRegion: ActorRef[ShardingEnvelope[Account.AccountCommand]] =
    sharding.init(Entity(AccountTypeKey)(createBehavior = entityContext => Account(entityContext.entityId)(transactionMessenger, () => DateUtils.now())))
  val transactionShardRegion: ActorRef[ShardingEnvelope[Transaction.TransactionCommand]] =
    sharding.init(Entity(TransactionTypeKey)(createBehavior = entityContext => Transaction(entityContext.entityId)(accountMessenger, resultMessenger)))

  def transactionMessenger(transactionId: String, message: AccountingStatus): Unit = message match {
    case AccountingSuccessful(cmdHash, accountId, availableBalance, currentBalance, _, timestamp) =>
      sharding.entityRefFor(TransactionTypeKey, transactionId) ! AcceptAccounting(cmdHash, accountId, ResultingBalance(availableBalance, currentBalance), timestamp)
    case AccountingFailed(cmdHash, accountId, code) =>
      sharding.entityRefFor(TransactionTypeKey, transactionId) ! RejectAccounting(cmdHash, accountId, code)
  }

  def accountMessenger(accountId: String, message: AccountingCommand): Unit = {
    sharding.entityRefFor(AccountTypeKey, accountId) ! message
  }

  def resultMessenger(message: TransactionResult): Unit = {
    //TODO: outgoing (kafka?)
  }


  val resumeOnParsingException = ActorAttributes.supervisionStrategy {
    case _: com.google.protobuf.InvalidProtocolBufferException => Supervision.Resume
    case _ => Supervision.Stop
  }

  val consumerConfig = system.settings.config.getConfig("akka.kafka.consumer")
  val consumerSettings: ConsumerSettings[String, Array[Byte]] = ConsumerSettings(
    config = consumerConfig, //TODO kafka configuration
    keyDeserializer = new StringDeserializer,
    valueDeserializer = new ByteArrayDeserializer)

  val committerConfig = system.settings.config.getConfig("akka.kafka.committer")
  val committerSettings = CommitterSettings(
    config = committerConfig //TODO kafka comitter configuration
  )

  val restartSettings = RestartSettings(
    minBackoff = 1.second,
    maxBackoff = 60.seconds,
    randomFactor = .5
  )

  val consumerActor: Future[ActorRef[StreamIncoming]] =
    system.ask(SpawnProtocol.Spawn(StreamConsumer((id) => sharding.entityRefFor(TransactionTypeKey, id)),
      name = "StreamConsumer", props = Props.empty, _))

  val consumerKillSwitch: Future[(Consumer.Control, ActorRef[StreamIncoming])] = for (ref <- consumerActor) yield (prepareIncomingStream(ref), ref)
  consumerKillSwitch.onComplete({
    case Success((control, consumer)) => coordinatedShutdown.addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "shutdown-incoming") { () =>
      for (
        _ <- control.stop();
        _ <- consumer.ask(PrepareForShutdown);
        _ <- control.shutdown()
      ) yield Done
    }
    case Failure(exception) => //TODO Log
  })

  def prepareIncomingStream(consumerRef: ActorRef[StreamIncoming]): Consumer.Control =
    Consumer.plainSource(consumerSettings, Subscriptions.topics("")) //TODO topics
      .map(consumerRecord => TransactionRequest.parseFrom(consumerRecord.value()).operation)
      .withAttributes(resumeOnParsingException)
      .toMat(ActorSink.actorRefWithBackpressure(
        ref = consumerRef,
        onInitMessage = replyTo => StreamInitialized(replyTo),
        ackMessage = Transaction.Ack,
        onCompleteMessage = StreamCompleted,
        onFailureMessage = ex => StreamFailure(ex),
        messageAdapter = (replyTo: ActorRef[TxnAck], message: TransactionRequest.Operation) => message match {
          case Operation.Empty =>
            NoOp(replyTo)
          case Operation.Simple(value) =>
            Op(value.transactionId, Begin(value.entryCode, value.accountToDebit, value.accountToCredit, value.amount, replyTo))
          case Operation.Authorize(value) =>
            Op(value.transactionId, Begin(value.entryCode, value.accountToDebit, value.accountToCredit, value.amount, replyTo, authOnly = true))
          case Operation.Capture(value) =>
            Op(value.transactionId, Capture(value.amountToCapture, replyTo))
          case Operation.Reverse(value) =>
            Op(value.transactionId, Reverse(replyTo))
          case Operation.Resume(value) =>
            Op(value.transactionId, Resume(replyTo))
        }
      ))(Keep.left)
      .run()


}
