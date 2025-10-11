#!/bin/bash

# Create Kafka topic for microstructure features
# Run on server: 13.203.60.173

KAFKA_HOME="/opt/kafka"
BROKER="localhost:9092"

echo "Creating microstructure-features topic..."

$KAFKA_HOME/bin/kafka-topics.sh --create \
  --bootstrap-server $BROKER \
  --replication-factor 1 \
  --partitions 3 \
  --topic microstructure-features \
  --config retention.ms=604800000 \
  --config compression.type=lz4

echo "✅ Topic created: microstructure-features"

# List topic to verify
echo ""
echo "Verifying topic..."
$KAFKA_HOME/bin/kafka-topics.sh --describe \
  --bootstrap-server $BROKER \
  --topic microstructure-features

echo ""
echo "✅ Microstructure features topic ready!"

