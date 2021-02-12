package io.openledger.simulator

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Scheduler, SpawnProtocol}
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.{Get, Post}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.kafka.scaladsl.{Consumer, Producer}
import akka.kafka.{ConsumerSettings, ProducerMessage, ProducerSettings, Subscriptions}
import akka.stream.scaladsl.{Keep, Merge, Sink, Source}
import io.openledger.AccountingMode
import io.openledger.api.http.Operations.{AccountResponse, AdjustRequest, OpenAccountRequest}
import io.openledger.api.http.{JsonSupport, Operations}
import io.openledger.kafka_operations.EntryRequest.Operation
import io.openledger.kafka_operations.{Capture, EntryRequest, EntryResult}
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
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContextExecutor}
import scala.util.Random

object Application extends App with JsonSupport {
  val ledgerHost = sys.env.getOrElse("LEDGER_HTTP_HOST", "openledger:8080")
  val kafkaBootstraps = sys.env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092")
  val numDebitAccounts = sys.env.getOrElse("DEBIT_ACCOUNTS", "10").toInt
  val numCreditAccounts = sys.env.getOrElse("CREDIT_ACCOUNTS", "10").toInt
  val iterations = sys.env.getOrElse("ITERATIONS", "100").toInt

  val logger = LoggerFactory.getLogger(this.getClass)

  implicit val system: ActorSystem[SpawnProtocol.Command] = ActorSystem(
    Behaviors.setup[SpawnProtocol.Command] { _ =>
      SpawnProtocol()
    },
    "simulator"
  )
  implicit val scheduler: Scheduler = system.scheduler
  implicit val executionContext: ExecutionContextExecutor = system.executionContext
  val http = Http()

  val accountUrl = s"http://${ledgerHost}/accounts/"

  var openRequests = Seq[(String, OpenAccountRequest, AdjustRequest)]()
  var accounts = Seq[String]()
  for (_ <- 0 until numCreditAccounts) {
    val id = UUID.randomUUID().toString
    openRequests = openRequests :+ (id, Operations.OpenAccountRequest(AccountingMode.CREDIT, id, Set.empty), Operations
      .AdjustRequest(AccountingMode.CREDIT, UUID.randomUUID().toString, "ADJUSTMENT", 1000))
    accounts = accounts :+ id
  }
  for (_ <- 0 until numDebitAccounts) {
    val id = UUID.randomUUID().toString
    openRequests = openRequests :+ (id, Operations.OpenAccountRequest(AccountingMode.DEBIT, id, Set.empty), Operations
      .AdjustRequest(AccountingMode.DEBIT, UUID.randomUUID().toString, "ADJUSTMENT", 1000))
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

  logger.info(s"Preparing requests.")
  logger.info(s"${iterations} iterations of ${TransferRoundTrip(Random.shuffle(accounts)).toString}")
  val transfer =
    Source(1 to iterations).map(_ => TransferRoundTrip(Random.shuffle(accounts)).generate()).mapConcat(identity)
  logger.info(s"${iterations} iterations of ${TransferReverse(Random.shuffle(accounts)).toString}")
  val transferRev =
    Source(1 to iterations).map(_ => TransferReverse(Random.shuffle(accounts)).generate()).mapConcat(identity)
  logger.info(s"${iterations} iterations of ${AuthCaptureRoundTrip(Random.shuffle(accounts)).toString}")
  val authCap =
    Source(1 to iterations).map(_ => AuthCaptureRoundTrip(Random.shuffle(accounts)).generate()).mapConcat(identity)
  //  logger.info(s"${iterations} iterations of ${AuthCaptureReverse(Random.shuffle(accounts)).toString}")
  //  val authCapRev = Source(1 to iterations).map(_ => AuthCaptureReverse(Random.shuffle(accounts)).generate()).mapConcat(identity)
  logger.info(s"${iterations} iterations of ${AuthPartialCaptureRoundTrip(Random.shuffle(accounts)).toString}")
  val authPcap = Source(1 to iterations)
    .map(_ => AuthPartialCaptureRoundTrip(Random.shuffle(accounts)).generate())
    .mapConcat(identity)
  //  logger.info(s"${iterations} iterations of ${AuthPartialCaptureReverse(Random.shuffle(accounts)).toString}")
  //  val authPcapRev = Source(1 to iterations).map(_ => AuthPartialCaptureReverse(Random.shuffle(accounts)).generate()).mapConcat(identity)
  logger.info(s"${iterations} iterations of ${AuthReverse(Random.shuffle(accounts)).toString}")
  val authRev = Source(1 to iterations).map(_ => AuthReverse(Random.shuffle(accounts)).generate()).mapConcat(identity)

  var capturables = Set[String]()
  val startTime = OffsetDateTime.now()
  logger.info(s"Sending requests. This may take a while...")
  val merged: (Map[String, Int], Int) = Await.result(
    Source
      .combine(
        transfer,
        transferRev,
        authCap,
        //      authCapRev,
        authPcap,
        //      authPcapRev,
        authRev
      )(Merge(_))
      .log("REQUESTS")
      .filter(p =>
        p.operation match {
          case Operation.Capture(value) => {
            capturables += value.entryId
            false
          }
          case _ => true
        }
      )
      .map(result =>
        ProducerMessage.single(
          new ProducerRecord(
            "openledger_incoming",
            result.operation match {
              case Operation.Simple(value)    => value.entryId
              case Operation.Authorize(value) => value.entryId
              case Operation.Reverse(value)   => value.entryId
              case _                          => throw new IllegalArgumentException()
            },
            result.toByteArray
          )
        )
      )
      .via(
        Producer.flexiFlow(
          ProducerSettings(
            config = system.settings.config.getConfig("akka.kafka.producer"),
            keySerializer = new StringSerializer,
            valueSerializer = new ByteArraySerializer
          ).withBootstrapServers(kafkaBootstraps)
        )
      )
      .map(_ => 1)
      .toMat(Sink.fold[Int, Int](0)(_ + _))(Keep.right)
      .run()
      .flatMap(x => {
        val n = x + accounts.size + capturables.size
        logger.info(s"Sent $n messages; waiting for responses. This may take a while...")
        Consumer
          .plainSource(
            ConsumerSettings(
              config = system.settings.config.getConfig("akka.kafka.consumer"),
              keyDeserializer = new StringDeserializer,
              valueDeserializer = new ByteArrayDeserializer
            ).withBootstrapServers(kafkaBootstraps)
              .withGroupId("simulator")
              .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true")
              .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
            Subscriptions.topics("openledger_outgoing")
          )
          .log(s"$n RESPONSES")
          .take(n)
          .map(consumerRecord => {
            val result = EntryResult.parseFrom(consumerRecord.value())
            if (result.status == "REJECTED" || result.status == "FAILED") {
              logger.error(s"${result.entryId} problem: ${result.code}")
            }
            if (capturables.contains(result.entryId)) {
              capturables -= result.entryId
              Source(
                Seq(
                  new ProducerRecord(
                    "openledger_incoming",
                    result.entryId,
                    EntryRequest(
                      Operation.Capture(
                        Capture(entryId = result.entryId, amountToCapture = 1)
                      )
                    ).toByteArray
                  )
                )
              ).runWith(
                Producer.plainSink(
                  ProducerSettings(
                    config = system.settings.config.getConfig("akka.kafka.producer"),
                    keySerializer = new StringSerializer,
                    valueSerializer = new ByteArraySerializer
                  ).withBootstrapServers(kafkaBootstraps)
                )
              )
            }
            result.status
          })
          .groupBy(10, identity)
          .map(_ -> 1)
          .reduce((l, r) => (l._1, l._2 + r._2))
          .mergeSubstreams
          .runWith(Sink.collection[(String, Int), Map[String, Int]])
          .map(r => (r, n))
      }),
    24.hours
  )

  logger.info(s"Asserting ${accounts.size} accounts")
  val endTime = OffsetDateTime.now()

  accounts
    .map(r => http.singleRequest(Get(s"$accountUrl$r")))
    .map(r =>
      r.flatMap(Unmarshal(_).to[String])
        .map(s => s.parseJson.convertTo[AccountResponse])
        .map(r => {
          if (r.balance.available != 1000 || r.balance.current != 1000) {
            logger.error(s"Account ${r.id} failed. Balance: ${r.balance.available} / ${r.balance.current}")
          } else {
            logger.info(s"Account ${r.id} passed")
          }
        })
    )
    .map(f => Await.result(f, 10.seconds))
  logger.info(s"DONE: ${merged._2} entrys")
  logger.info(s"Start: $startTime")
  logger.info(s"End: $endTime")
  logger.info(s"Elapsed: ${startTime.until(endTime, ChronoUnit.SECONDS)} seconds")
  logger.info(merged._1.toString())

  system.terminate()
}
