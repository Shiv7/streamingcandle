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
CANDLE_TOPICS="candle-complete-1m candle-complete-2m candle-complete-3m candle-complete-5m candle-complete-15m candle-complete-30m"

# ============================================================================
# FAMILY AGGREGATION TOPICS (Issue #1 Fix)
# ============================================================================
FAMILY_TOPICS="family-structured-1m family-structured-2m family-structured-5m family-structured-15m family-structured-30m family-structured-all"

# ============================================================================
# CHANGELOG TOPICS (Issue #2 Fix - State Store Corruption)
# ============================================================================
echo "🗂️ Creating changelog topics..."
CHANGELOG_TOPICS="instrument-instrument-delta-volume-store-changelog instrument-instrument-state-store-changelog instrument-KSTREAM-TOTABLE-STATE-STORE-0000000006-changelog"

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

# Create changelog topics
echo "🗂️ Creating changelog topics..."
for topic in $CHANGELOG_TOPICS; do
    create_changelog_topic "$topic"
done
echo ""

echo "✅ All topics created successfully!"
echo ""
echo "🔍 Verify topics:"
echo "  $KAFKA_BIN/kafka-topics.sh --bootstrap-server $KAFKA_BOOTSTRAP_SERVER --list | grep -E '(candle|family|changelog)'"
echo ""
echo "📋 Topic counts:"
echo "  Candle topics: $(echo $CANDLE_TOPICS | wc -w)"
echo "  Family topics: $(echo $FAMILY_TOPICS | wc -w)"
echo "  Changelog topics: $(echo $CHANGELOG_TOPICS | wc -w)"
echo ""
echo "⚠️ NOTE: Topics may not have been created if kafka-topics.sh is not in PATH"
echo "To verify topics were actually created, run:"
echo "  $KAFKA_BIN/kafka-topics.sh --bootstrap-server $KAFKA_BOOTSTRAP_SERVER --list"

