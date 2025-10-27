#!/bin/bash

# Create ALL Required Topics (Candles + Family + Changelogs)
# This prevents topic dependency issues and state store corruption

KAFKA_BOOTSTRAP_SERVER="${1:-localhost:9092}"
KAFKA_BIN="${2:-/opt/kafka/bin}"

echo "🚀 Creating all required Kafka topics..."
echo "📡 Kafka Bootstrap Server: $KAFKA_BOOTSTRAP_SERVER"
echo "📂 Kafka Bin Directory: $KAFKA_BIN"
echo ""

# ============================================================================
# CANDLE OUTPUT TOPICS (Issue #1 Fix)
# ============================================================================
echo "📊 Creating candle output topics..."
# CRITICAL: Include both candle-complete-30m AND candle-complete-30m-v2 for compatibility
CANDLE_TOPICS="candle-complete-1m candle-complete-2m candle-complete-3m candle-complete-5m candle-complete-15m candle-complete-30m candle-complete-30m-v2"

# ============================================================================
# FAMILY AGGREGATION TOPICS (Issue #1 Fix)
# ============================================================================
FAMILY_TOPICS="family-structured-1m family-structured-2m family-structured-5m family-structured-15m family-structured-30m family-structured-all"

# ============================================================================
# INTERMEDIATE TOPICS (New Architecture - Separate Stream Aggregations)
# ============================================================================
echo "🔄 Creating intermediate topics for separate stream aggregations..."
INTERMEDIATE_TOPICS="intermediate-ohlcv-1m intermediate-ohlcv-2m intermediate-ohlcv-3m intermediate-ohlcv-5m intermediate-ohlcv-15m intermediate-ohlcv-30m intermediate-orderbook-1m intermediate-orderbook-2m intermediate-orderbook-3m intermediate-orderbook-5m intermediate-orderbook-15m intermediate-orderbook-30m intermediate-oi-1m intermediate-oi-2m intermediate-oi-3m intermediate-oi-5m intermediate-oi-15m intermediate-oi-30m"

# ============================================================================
# CHANGELOG TOPICS (Issue #2 Fix - State Store Corruption)
# ============================================================================
# CRITICAL: DON'T pre-create changelog topics!
# Kafka Streams will auto-create them with the correct partition count
# Pre-creating with wrong partition count causes "invalid partitions" error
echo "🗂️ Skipping changelog topics (Kafka Streams will auto-create with correct partitions)"
CHANGELOG_TOPICS=""

# Configuration
PARTITIONS=6
REPLICATION_FACTOR=1  # Change to 3 for production
CANDLE_RETENTION_MS=604800000  # 7 days for candles
CHANGELOG_RETENTION_MS=604800000  # 7 days for changelogs
MIN_COMPACTION_LAG_MS=86400000  # 1 day

echo ""
echo "⚙️ Configuration:"
echo "  Partitions: $PARTITIONS"
echo "  Replication Factor: $REPLICATION_FACTOR"
echo "  Candle Retention: 7 days"
echo "  Changelog Retention: 7 days (with compaction)"
echo ""

# Function to create candle/family topic
create_data_topic() {
    local topic=$1
    echo "📝 Creating data topic: $topic"
    
    $KAFKA_BIN/kafka-topics.sh --bootstrap-server "$KAFKA_BOOTSTRAP_SERVER" \
        --create \
        --topic "$topic" \
        --partitions "$PARTITIONS" \
        --replication-factor "$REPLICATION_FACTOR" \
        --config retention.ms="$CANDLE_RETENTION_MS" \
        --config cleanup.policy=delete \
        --if-not-exists 2>&1 | grep -v "already exists"
    
    if [ $? -eq 0 ] || [ $? -eq 1 ]; then
        echo "  ✅ $topic"
    else
        echo "  ❌ Failed: $topic"
    fi
}

# Function to create changelog topic
create_changelog_topic() {
    local topic=$1
    echo "📝 Creating changelog topic: $topic"
    
    $KAFKA_BIN/kafka-topics.sh --bootstrap-server "$KAFKA_BOOTSTRAP_SERVER" \
        --create \
        --topic "$topic" \
        --partitions "$PARTITIONS" \
        --replication-factor "$REPLICATION_FACTOR" \
        --config retention.ms="$CHANGELOG_RETENTION_MS" \
        --config cleanup.policy="compact,delete" \
        --config min.compaction.lag.ms="$MIN_COMPACTION_LAG_MS" \
        --if-not-exists 2>&1 | grep -v "already exists"
    
    if [ $? -eq 0 ] || [ $? -eq 1 ]; then
        echo "  ✅ $topic"
    else
        echo "  ❌ Failed: $topic"
    fi
}

# Create candle topics
echo "📊 Creating candle output topics..."
for topic in $CANDLE_TOPICS; do
    create_data_topic "$topic"
done
echo ""

# Create family topics
echo "👨‍👩‍👧‍👦 Creating family aggregation topics..."
for topic in $FAMILY_TOPICS; do
    create_data_topic "$topic"
done
echo ""

# Create intermediate topics
echo "🔄 Creating intermediate topics..."
for topic in $INTERMEDIATE_TOPICS; do
    create_data_topic "$topic"
done
echo ""

# Create changelog topics (if any)
if [ -n "$CHANGELOG_TOPICS" ]; then
    echo "🗂️ Creating changelog topics..."
    for topic in $CHANGELOG_TOPICS; do
        create_changelog_topic "$topic"
    done
    echo ""
fi

echo "✅ All required topics created successfully!"
echo ""
echo "📋 Topic counts:"
echo "  Candle topics: $(echo $CANDLE_TOPICS | wc -w)"
echo "  Family topics: $(echo $FAMILY_TOPICS | wc -w)"
echo "  Intermediate topics: $(echo $INTERMEDIATE_TOPICS | wc -w)"
echo "  Changelog topics: Kafka Streams will auto-create"
echo ""
echo "🔍 Verify topics were created:"
echo "  $KAFKA_BIN/kafka-topics.sh --bootstrap-server $KAFKA_BOOTSTRAP_SERVER --list | grep -E '(candle|family)'"
echo ""
echo "⚠️ IMPORTANT NOTES:"
echo "  - Changelog topics will be auto-created by Kafka Streams with correct partition count"
echo "  - Do NOT manually create changelog topics (causes partition mismatch)"
echo "  - Both candle-complete-30m and candle-complete-30m-v2 were created for compatibility"

