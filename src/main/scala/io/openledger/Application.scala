package io.openledger

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import io.openledger.account.Account
import io.openledger.account.Account.{AccountingCommand, AccountingFailed, AccountingStatus, AccountingSuccessful}
import io.openledger.transaction.Transaction
import io.openledger.transaction.Transaction.{AcceptAccounting, RejectAccounting, ResultingBalance, TransactionResult}

class Application extends App {
  //#actor-system
  val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "OpenLedger")
  val sharding = ClusterSharding(system)

  val AccountTypeKey = EntityTypeKey[Account.AccountCommand]("Account")
  val TransactionTypeKey = EntityTypeKey[Transaction.TransactionCommand]("Transaction")
  val accountShardRegion: ActorRef[ShardingEnvelope[Account.AccountCommand]] =
    sharding.init(Entity(AccountTypeKey)(createBehavior = entityContext => Account(entityContext.entityId)(transactionMessenger, () => DateUtils.now())))
  val transactionShardRegion: ActorRef[ShardingEnvelope[Transaction.TransactionCommand]] =
    sharding.init(Entity(TransactionTypeKey)(createBehavior = entityContext => Transaction(entityContext.entityId)(accountMessenger, resultMessenger)))

  def transactionMessenger(transactionId: String, message: AccountingStatus): Unit = message match {
    case AccountingSuccessful(accountId, availableBalance, currentBalance, _, _) =>
      sharding.entityRefFor(TransactionTypeKey, transactionId) ! AcceptAccounting(accountId, ResultingBalance(availableBalance, currentBalance))
    case AccountingFailed(accountId, code) =>
      sharding.entityRefFor(TransactionTypeKey, transactionId) ! RejectAccounting(accountId, code)
  }

  def accountMessenger(accountId: String, message: AccountingCommand): Unit = {
    sharding.entityRefFor(AccountTypeKey, accountId) ! message
  }

  def resultMessenger(message: TransactionResult): Unit = {

  }
}
