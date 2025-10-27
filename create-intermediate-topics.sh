#!/bin/bash

# Create intermediate topics for streaming candle architecture

BOOTSTRAP_SERVER="localhost:9092"
PARTITIONS=1
REPLICATION_FACTOR=1

echo "Creating intermediate topics..."

# Create OHLCV topics
for tf in 1m 2m 3m 5m 15m 30m; do
    echo "Creating intermediate-ohlcv-${tf}"
    kafka-topics --create \
        --bootstrap-server $BOOTSTRAP_SERVER \
        --topic intermediate-ohlcv-${tf} \
        --partitions $PARTITIONS \
        --replication-factor $REPLICATION_FACTOR \
        --if-not-exists
done

# Create Orderbook topics
for tf in 1m 2m 3m 5m 15m 30m; do
    echo "Creating intermediate-orderbook-${tf}"
    kafka-topics --create \
        --bootstrap-server $BOOTSTRAP_SERVER \
        --topic intermediate-orderbook-${tf} \
        --partitions $PARTITIONS \
        --replication-factor $REPLICATION_FACTOR \
        --if-not-exists
done

# Create OI topics
for tf in 1m 2m 3m 5m 15m 30m; do
    echo "Creating intermediate-oi-${tf}"
    kafka-topics --create \
        --bootstrap-server $BOOTSTRAP_SERVER \
        --topic intermediate-oi-${tf} \
        --partitions $PARTITIONS \
        --replication-factor $REPLICATION_FACTOR \
        --if-not-exists
done

echo "✅ All intermediate topics created!"
echo ""
echo "Verify with:"
echo "kafka-topics --list --bootstrap-server $BOOTSTRAP_SERVER | grep intermediate"
