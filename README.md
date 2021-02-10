# OpenLedger

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
docker run -e JAVA_OPTS="-Dconfig.resource=/application-local.conf" --network="openledger_default" -p8080:8080 --name openledger openledger:1.0.0

# Run processor image (defaults to 2 iterations, 2 debit accounts, 2 credit accounts)
docker run --network="openledger_default" -p8080:8080 -e ITERATIONS=3 --name simulator simulator:1.0.0
```