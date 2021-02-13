package io.openledger.projection

import akka.actor.CoordinatedShutdown
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Scheduler, SpawnProtocol}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import com.zaxxer.hikari.HikariDataSource
import io.openledger.projection.account.{
  AccountInfoRepository,
  AccountOverdraftRepository,
  AccountProjection,
  AccountStatementRepository
}
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContextExecutor

object Application extends App {
  val logger = LoggerFactory.getLogger(this.getClass)

  implicit val system: ActorSystem[SpawnProtocol.Command] = ActorSystem(
    Behaviors.setup[SpawnProtocol.Command] { _ =>
      SpawnProtocol()
    },
    "openledger-projection"
  )
  implicit val scheduler: Scheduler = system.scheduler
  implicit val executionContext: ExecutionContextExecutor = system.executionContext
  AkkaManagement(system).start()
  ClusterBootstrap(system).start()
  val coordinatedShutdown = CoordinatedShutdown(system)
  val sharding = ClusterSharding(system)

  val dbConf = system.settings.config.getConfig("ledger-settings.database")

  val ds = new HikariDataSource()
  ds.setJdbcUrl(dbConf.getString("url"))
  ds.setUsername(dbConf.getString("username"))
  ds.setPassword(dbConf.getString("password"))
  ds.setAutoCommit(false)
  ds.setMaximumPoolSize(dbConf.getInt("number-of-connections"))
  ds.setMinimumIdle(dbConf.getInt("number-of-connections"))

  AccountProjection(ds, AccountInfoRepository(), AccountStatementRepository(), AccountOverdraftRepository())
}
