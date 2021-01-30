package io.openledger

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import io.openledger.account.Account
import io.openledger.account.Account.{AccountingCommand, AccountingStatus}
import io.openledger.transaction.Transaction
import io.openledger.transaction.Transaction.AccountingResult

class Application extends App {
  //#actor-system
  val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "OpenLedger")
  val sharding = ClusterSharding(system)

  val AccountTypeKey = EntityTypeKey[Account.AccountCommand]("Account")
  val TransactionTypeKey = EntityTypeKey[Transaction.TransactionCommand]("Transaction")
  val accountShardRegion: ActorRef[ShardingEnvelope[Account.AccountCommand]] =
    sharding.init(Entity(AccountTypeKey)(createBehavior = entityContext => Account(entityContext.entityId)(transactionMessenger)))
  val transactionShardRegion: ActorRef[ShardingEnvelope[Transaction.TransactionCommand]] =
    sharding.init(Entity(TransactionTypeKey)(createBehavior = entityContext => Transaction(entityContext.entityId)(accountMessenger)))

  def transactionMessenger(transactionId: String, message: AccountingStatus): Unit = {
    sharding.entityRefFor(TransactionTypeKey, transactionId) ! AccountingResult(message)
  }

  def accountMessenger(accountId: String, message: AccountingCommand) {
    sharding.entityRefFor(AccountTypeKey, accountId) ! message
  }
}
