#!/bin/bash

# CRITICAL FIX: Script to create all required Kafka topics for information bars
# BUG #11 Fix: Topics must exist before starting streamingcandle
#
# Usage:
#   ./create_information_bar_topics.sh

set -e  # Exit on error

KAFKA_HOST="13.203.60.173:9092"
PARTITIONS=10
REPLICATION_FACTOR=1

echo "================================================"
echo "Creating Information Bar Kafka Topics"
echo "================================================"
echo "Kafka Host: $KAFKA_HOST"
echo "Partitions: $PARTITIONS"
echo "Replication Factor: $REPLICATION_FACTOR"
echo ""

# Function to create topic
create_topic() {
    TOPIC_NAME=$1
    echo "Creating topic: $TOPIC_NAME"
    
    kafka-topics.sh --create \
        --bootstrap-server $KAFKA_HOST \
        --topic $TOPIC_NAME \
        --partitions $PARTITIONS \
        --replication-factor $REPLICATION_FACTOR \
        --if-not-exists \
        --config retention.ms=604800000 \
        --config compression.type=lz4
    
    if [ $? -eq 0 ]; then
        echo "✅ Topic $TOPIC_NAME created successfully"
    else
        echo "⚠️  Topic $TOPIC_NAME might already exist or creation failed"
    fi
    echo ""
}

# Create all information bar topics
create_topic "volume-imbalance-bars"
create_topic "dollar-imbalance-bars"
create_topic "tick-runs-bars"
create_topic "volume-runs-bars"

echo "================================================"
echo "✅ All topics created/verified"
echo "================================================"
echo ""
echo "Verify topics exist:"
echo "  kafka-topics.sh --bootstrap-server $KAFKA_HOST --list | grep bars"
echo ""
echo "Describe a topic:"
echo "  kafka-topics.sh --bootstrap-server $KAFKA_HOST --describe --topic volume-imbalance-bars"
echo ""

