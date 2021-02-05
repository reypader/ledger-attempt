package io.openledger

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import io.openledger.domain.account.Account
import io.openledger.domain.account.Account.{AccountingCommand, AccountingFailed, AccountingStatus, AccountingSuccessful}
import io.openledger.domain.transaction.Transaction
import io.openledger.domain.transaction.Transaction.{AcceptAccounting, RejectAccounting, TransactionResult}

class Application extends App {
  val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "OpenLedger")
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

  }
}
