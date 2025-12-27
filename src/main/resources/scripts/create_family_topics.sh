#!/bin/bash
# =============================================================================
# create_family_topics.sh
# Create Kafka topics for the NEW Unified Family Candle Architecture
# 
# IMPORTANT: Run with bash, not sh:  bash create_family_topics.sh
# Usage: bash create_family_topics.sh [--delete-first]
# =============================================================================

# Configuration
KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:-13.203.60.173:9094}"
KAFKA_BIN="${KAFKA_BIN_DIR:-/opt/kafka/bin}"  # Adjust if kafka is installed elsewhere
PARTITIONS=3
REPLICATION_FACTOR=1

# NEW Family Candle Topics
FAMILY_TOPICS="instrument-candle-1m
family-candle-1m
family-candle-2m
family-candle-3m
family-candle-5m
family-candle-15m
family-candle-30m
family-candle-1h
family-candle-2h
family-candle-4h
family-candle-1d
curated-trading-signals"

# Consumer groups for the new pipeline
NEW_APP_IDS="unified-family-processor-v1
unified-family-processor-v2
timeframe-aggregator-1m
timeframe-aggregator-2m
timeframe-aggregator-3m
timeframe-aggregator-5m
timeframe-aggregator-15m
timeframe-aggregator-30m
timeframe-aggregator-1h
timeframe-aggregator-2h
timeframe-aggregator-4h
timeframe-aggregator-1d
family-candle-processor"

# RocksDB state store locations for new processors
ROCKSDB_PATHS="/tmp/kafka-streams/streamingcandle"

DELETE_FIRST=false
if [ "$1" = "--delete-first" ]; then
    DELETE_FIRST=true
    echo "=== Will delete topics before creating ===" 
fi

echo "============================================="
echo "  Family Candle Topic Creation Script"
echo "============================================="
echo ""
echo "Kafka Bootstrap: $KAFKA_BOOTSTRAP"
echo "Partitions: $PARTITIONS"
echo "Replication Factor: $REPLICATION_FACTOR"
echo ""

# =========================================================================
# Step 0: (Optional) Delete topics first
# =========================================================================
if [ "$DELETE_FIRST" = true ]; then
    echo "Step 0: Deleting existing topics..."
    
    echo "$FAMILY_TOPICS" | while read topic; do
        if [ -n "$topic" ]; then
            printf "  Deleting $topic... "
            if $KAFKA_BIN/kafka-topics.sh --bootstrap-server $KAFKA_BOOTSTRAP \
                --delete --topic "$topic" 2>/dev/null; then
                echo "DELETED"
            else
                echo "SKIPPED (not found)"
            fi
        fi
    done
    
    echo ""
    echo "  Waiting 10s for deletions to propagate..."
    sleep 10
    echo ""
fi

# =========================================================================
# Step 1: Clean up RocksDB state stores
# =========================================================================
echo "Step 1: Cleaning RocksDB state stores..."

echo "$ROCKSDB_PATHS" | while read path; do
    if [ -n "$path" ]; then
        if [ -d "$path" ]; then
            printf "  Removing $path... "
            if rm -rf "$path" 2>/dev/null; then
                echo "OK"
            else
                echo "FAILED (may need sudo)"
            fi
        else
            echo "  $path does not exist, skipping"
        fi
    fi
done

echo ""

# =========================================================================
# Step 2: Reset consumer group offsets to earliest
# =========================================================================
echo "Step 2: Resetting consumer group offsets to earliest..."

echo "$NEW_APP_IDS" | while read app_id; do
    if [ -n "$app_id" ]; then
        printf "  Resetting $app_id... "
        if $KAFKA_BIN/kafka-consumer-groups.sh --bootstrap-server $KAFKA_BOOTSTRAP \
            --group "$app_id" --reset-offsets --to-earliest --all-topics --execute 2>/dev/null; then
            echo "OK"
        else
            echo "SKIPPED (group may not exist)"
        fi
    fi
done

echo ""

# =========================================================================
# Step 3: Create family candle topics
# =========================================================================
echo "Step 3: Creating family candle topics..."

CREATED=0
EXISTED=0
FAILED=0

echo "$FAMILY_TOPICS" | while read topic; do
    if [ -n "$topic" ]; then
        printf "  Creating $topic... "
        
        # Check if topic exists
        if $KAFKA_BIN/kafka-topics.sh --bootstrap-server $KAFKA_BOOTSTRAP --describe --topic "$topic" 2>/dev/null | grep -q "Topic: $topic"; then
            echo "EXISTS"
            EXISTED=$((EXISTED + 1))
        else
            # Create topic
            if $KAFKA_BIN/kafka-topics.sh --bootstrap-server $KAFKA_BOOTSTRAP \
                --create --topic "$topic" \
                --partitions $PARTITIONS \
                --replication-factor $REPLICATION_FACTOR 2>/dev/null; then
                echo "CREATED"
                CREATED=$((CREATED + 1))
            else
                echo "FAILED"
                FAILED=$((FAILED + 1))
            fi
        fi
    fi
done

echo ""
echo "============================================="
echo "  Family Candle Topic Creation Complete"
echo "============================================="
echo ""
echo "Topics Summary:"
echo "  - instrument-candle-1m (raw unified candles)"
echo "  - family-candle-{1m,2m,3m,5m,15m,30m,1h,2h,4h,1d} (family candles)"
echo "  - curated-trading-signals (high-quality signals)"
echo ""
echo "Next Steps:"
echo "  1. Start streamingcandle:"
echo "     java -jar streamingcandle.jar"
echo ""
echo "  2. Monitor family candles:"
echo "     $KAFKA_BIN/kafka-console-consumer.sh --bootstrap-server $KAFKA_BOOTSTRAP --topic family-candle-5m"
echo ""
echo "  3. Monitor curated signals:"
echo "     $KAFKA_BIN/kafka-console-consumer.sh --bootstrap-server $KAFKA_BOOTSTRAP --topic curated-trading-signals"
echo ""
