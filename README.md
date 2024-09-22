# Ledger Attempt
My attempt to build a ledger using Akka to test my knowledge on both the domain and the framework.

## How to build

```shell
sbt clean docker:publishLocal
```

## How to run

```shell
# Run dependencies (Kafka, PostgresSQL)
# Creates `openledger_default` network
docker-compose up --remove-orphans -d

# Run processor image
docker run -e JAVA_OPTS="-Dconfig.resource=/application-local.conf" --network="openledger_default" --name openledger openledger:0.1-SNAPSHOT

# Run simulator image 
# to repoint to a different openledger host: -e LEDGER_HTTP_HOST=some.other.host:80
# to repoint to a different kafka bootstrap servers: -e KAFKA_BOOTSTRAP_SERVERS=bootstrap1:9092,bootstrap2:9092
docker run --network="openledger_default" -e ITERATIONS=100 -e DEBIT_ACCOUNTS=10 -e CREDIT_ACCOUNTS=10 --name simulator simulator:0.1-SNAPSHOT

# Run projection
# Inspect projection tables: ledger_account_statement, ledger_account_info, etc.
docker run -e JAVA_OPTS="-Dconfig.resource=/application-local.conf" --network="openledger_default" --name openledger-projection projection:0.1-SNAPSHOT
```
