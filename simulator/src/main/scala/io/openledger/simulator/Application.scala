package io.openledger.simulator

import akka.Done
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Props, Scheduler, SpawnProtocol}
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.{Get, Post}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.kafka.scaladsl.{Consumer, Producer}
import akka.kafka.{ConsumerSettings, ProducerMessage, ProducerSettings, Subscriptions}
import akka.stream.{BoundedSourceQueue, OverflowStrategy}
import akka.stream.scaladsl.{Keep, Merge, Sink, Source, SourceQueue}
import io.openledger.AccountingMode
import io.openledger.Application.{
  accountResolver,
  executionContext,
  resultMessenger,
  scheduler,
  sharding,
  streamConsumerSettings,
  system
}
import io.openledger.api.http.Operations.{AccountResponse, AdjustRequest, OpenAccountRequest}
import io.openledger.api.http.{JsonSupport, Operations}
import io.openledger.api.kafka.StreamConsumer
import io.openledger.api.kafka.StreamConsumer.StreamOp
import io.openledger.domain.entry.Entry.EntryTypeKey
import io.openledger.kafka_operations.EntryRequest.Operation
import io.openledger.kafka_operations.{Capture, EntryRequest, EntryResult, Reverse}
import io.openledger.simulator.Application.send
import io.openledger.simulator.Monitor.{MonitorOperation, SpawnCases}
import io.openledger.simulator.sequences._
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{
  ByteArrayDeserializer,
  ByteArraySerializer,
  StringDeserializer,
  StringSerializer
}
import org.slf4j.LoggerFactory
import spray.json.{enrichAny, _}

import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContextExecutor, Future, Promise}
import scala.util.Random

object Application extends App with JsonSupport {
  val ledgerHost = sys.env.getOrElse("LEDGER_HTTP_HOST", "openledger:8080")
  val kafkaBootstraps = sys.env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092")
  val numDebitAccounts = sys.env.getOrElse("DEBIT_ACCOUNTS", "10").toInt
  val numCreditAccounts = sys.env.getOrElse("CREDIT_ACCOUNTS", "10").toInt
  val iterations = sys.env.getOrElse("ITERATIONS", "100").toInt
  val startingBalances = 1000000
  val logger = LoggerFactory.getLogger(this.getClass)
  implicit val system: ActorSystem[SpawnProtocol.Command] = ActorSystem(
    Behaviors.setup[SpawnProtocol.Command] { _ =>
      SpawnProtocol()
    },
    "simulator"
  )
  implicit val scheduler: Scheduler = system.scheduler
  implicit val executionContext: ExecutionContextExecutor = system.executionContext

  val producerSettings: ProducerSettings[String, Array[Byte]] = ProducerSettings(
    config = system.settings.config.getConfig("akka.kafka.producer"),
    keySerializer = new StringSerializer,
    valueSerializer = new ByteArraySerializer
  ).withBootstrapServers(kafkaBootstraps)
  val consumerSettings: ConsumerSettings[String, Array[Byte]] = ConsumerSettings(
    config = system.settings.config.getConfig("akka.kafka.consumer"),
    keyDeserializer = new StringDeserializer,
    valueDeserializer = new ByteArrayDeserializer
  ).withBootstrapServers(kafkaBootstraps)
    .withGroupId("simulator")
    .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true")
    .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

  val http = Http()

  val accountUrl = s"http://$ledgerHost/accounts/"

  var openRequests = Seq[(String, OpenAccountRequest, AdjustRequest)]()
  var accounts = Seq[String]()
  for (_ <- 0 until numCreditAccounts) {
    val id = UUID.randomUUID().toString
    openRequests = openRequests :+ (id, Operations.OpenAccountRequest(AccountingMode.CREDIT, id, Set.empty), Operations
      .AdjustRequest(AccountingMode.CREDIT, UUID.randomUUID().toString, "ADJUSTMENT", startingBalances))
    accounts = accounts :+ id
  }
  for (_ <- 0 until numDebitAccounts) {
    val id = UUID.randomUUID().toString
    openRequests = openRequests :+ (id, Operations.OpenAccountRequest(AccountingMode.DEBIT, id, Set.empty), Operations
      .AdjustRequest(AccountingMode.DEBIT, UUID.randomUUID().toString, "ADJUSTMENT", startingBalances))
    accounts = accounts :+ id
  }

  logger.info(s"Preparing ${openRequests.size} accounts")

  val setupResults = openRequests
    .map(r =>
      http
        .singleRequest(Post(accountUrl, r._2.toJson))
        .flatMap(x => {
          if (!x.status.isSuccess()) {
            logger.error(s"Failed to open account: $x")
          }
          http.singleRequest(Post(s"$accountUrl${r._1}/adjustments", r._3.toJson))
        })
    )
    .map(f => Await.result(f, 10.seconds))
    .foreach(r =>
      if (!r.status.isSuccess()) {
        logger.error(s"Failed Setup account: $r")
      }
    )

  val startTime = OffsetDateTime.now()
//  val immedateSendCount = captures.size + reversals.size
//  val toFollowSendCount = reversibles.size + auths.size
  val routes: mutable.Map[String, ActorRef[EntryResult]] = mutable.Map()
  logger.info(s"Sending requests. This may take a while...")
  val q: SourceQueue[((String, ActorRef[EntryResult]), EntryRequest)] = Source
    .queue[((String, ActorRef[EntryResult]), EntryRequest)](1000, OverflowStrategy.backpressure)
    .log("REQUESTS")
    .map(result => {
      routes.addOne(result._1)
      val op = result._2.operation match {
        case s @ Operation.Simple(value)    => s.copy(value.copy(entryId = result._1._1))
        case a @ Operation.Authorize(value) => a.copy(value.copy(entryId = result._1._1))
        case r @ Operation.Reverse(value)   => r.copy(value.copy(entryId = result._1._1))
        case c @ Operation.Capture(value)   => c.copy(value.copy(entryId = result._1._1))
        case _                              => throw new IllegalArgumentException()
      }
      (result._1, EntryRequest(op))
    })
    .map(result =>
      new ProducerRecord(
        "openledger_incoming",
        result._2.operation match {
          case Operation.Simple(value)    => value.entryId
          case Operation.Authorize(value) => value.entryId
          case Operation.Reverse(value)   => value.entryId
          case Operation.Capture(value)   => value.entryId
          case _                          => throw new IllegalArgumentException()
        },
        result._2.toByteArray
      )
    )
    .toMat(
      Producer.plainSink(
        producerSettings
      )
    )(Keep.left)
    .run()

  val p = Promise[Map[String, Seq[Long]]]()
  val ref: ActorRef[MonitorOperation] = Await.result(
    system.ask((replyTo: ActorRef[ActorRef[MonitorOperation]]) =>
      SpawnProtocol.Spawn(
        Monitor(p),
        name = "Monitor",
        props = Props.empty,
        replyTo
      )
    )(10.seconds, scheduler),
    20.seconds
  )

  Consumer
    .plainSource(
      consumerSettings,
      Subscriptions.topics("openledger_outgoing")
    )
    .log("RESPONSES")
    .map(consumerRecord => {
      val result = EntryResult.parseFrom(consumerRecord.value())
      if (routes.contains(result.entryId)) {
        routes(result.entryId) ! result
      }
      result.status
    })
    .runWith(Sink.ignore)

  val acr = AuthCaptureReverse(accounts, q, iterations)
  logger.info(acr.toString)
  ref ! SpawnCases(acr.generate(ref))

  val ac = AuthCaptureRoundTrip(accounts, q, iterations)
  logger.info(ac.toString)
  ref ! SpawnCases(ac.generate(ref))

  val ar = AuthReverse(accounts, q, iterations)
  logger.info(ar.toString)
  ref ! SpawnCases(ar.generate(ref))

  val tr = TransferReverse(accounts, q, iterations)
  logger.info(tr.toString)
  ref ! SpawnCases(tr.generate(ref))

  val t = TransferRoundTrip(accounts, q, iterations)
  logger.info(t.toString)
  ref ! SpawnCases(t.generate(ref))

  logger.info(
    s"Waiting for responses. This may take a while..."
  )
  val latencies = Await.result(p.future, 24.hours)

  logger.info(s"Asserting ${accounts.size} accounts")
  val endTime = OffsetDateTime.now()

  accounts
    .map(r => http.singleRequest(Get(s"$accountUrl$r")))
    .map(r =>
      r.flatMap(Unmarshal(_).to[String])
        .map(s => s.parseJson.convertTo[AccountResponse])
        .map(r => {
          if (r.balance.available != startingBalances || r.balance.current != startingBalances) {
            logger.error(s"Account ${r.id} failed. Balance: ${r.balance.available} / ${r.balance.current}")
          } else {
            logger.info(s"Account ${r.id} passed")
          }
        })
    )
    .map(f => Await.result(f, 10.seconds))
  var replyCounts = Map.empty[String, Int]
  latencies.foreach { l =>
    logger.info(s"Average Latency: ${l._1} -- ${l._2.sum / l._2.size} milliseconds")
    replyCounts = replyCounts + (l._1 -> l._2.size)
  }
  val operations = replyCounts.values.sum
  logger.info(s"DONE: ${operations} operations")
  logger.info(s"Start: $startTime")
  logger.info(s"End: $endTime")
  logger.info(s"Elapsed: ${startTime.until(endTime, ChronoUnit.SECONDS)} seconds")
  logger.info(
    s"Effective TPS: ${(operations) / startTime.until(endTime, ChronoUnit.SECONDS)} operation per second"
  )
  logger.info(replyCounts.toString())

  system.terminate()

  private def send(r: EntryRequest, entryId: String): Future[Done] = {
    Source(
      Seq(
        new ProducerRecord(
          "openledger_incoming",
          entryId,
          r.toByteArray
        )
      )
    ).runWith(
      Producer.plainSink(
        producerSettings
      )
    )
  }
}
