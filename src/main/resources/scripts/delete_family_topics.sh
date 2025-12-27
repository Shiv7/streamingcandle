#!/bin/bash
# =============================================================================
# delete_family_topics.sh
# Delete all Kafka topics for the Family Candle Architecture
# 
# IMPORTANT: Run with bash, not sh:  bash delete_family_topics.sh
# Usage: bash delete_family_topics.sh [--force]
# =============================================================================

# Configuration
KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:-13.203.60.173:9094}"
KAFKA_BIN="${KAFKA_BIN_DIR:-/opt/kafka/bin}"

# Family Candle Topics to delete
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

# Consumer groups to delete
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

FORCE=false
if [ "$1" = "--force" ]; then
    FORCE=true
fi

echo "============================================="
echo "  Family Candle Topic Deletion Script"
echo "============================================="
echo ""
echo "Kafka Bootstrap: $KAFKA_BOOTSTRAP"
echo ""

if [ "$FORCE" = false ]; then
    echo "WARNING: This will delete all family candle topics!"
    echo "Topics to be deleted:"
    echo "$FAMILY_TOPICS" | while read topic; do
        echo "  - $topic"
    done
    echo ""
    read -p "Are you sure you want to continue? (y/N): " confirm
    if [ "$confirm" != "y" ] && [ "$confirm" != "Y" ]; then
        echo "Aborted."
        exit 0
    fi
fi

echo ""

# =========================================================================
# Step 1: Delete consumer groups
# =========================================================================
echo "Step 1: Deleting consumer groups..."

echo "$NEW_APP_IDS" | while read app_id; do
    if [ -n "$app_id" ]; then
        printf "  Deleting group $app_id... "
        if $KAFKA_BIN/kafka-consumer-groups.sh --bootstrap-server $KAFKA_BOOTSTRAP \
            --group "$app_id" --delete 2>/dev/null; then
            echo "DELETED"
        else
            echo "SKIPPED (not found)"
        fi
    fi
done

echo ""

# =========================================================================
# Step 2: Delete topics
# =========================================================================
echo "Step 2: Deleting family candle topics..."

DELETED=0
SKIPPED=0

echo "$FAMILY_TOPICS" | while read topic; do
    if [ -n "$topic" ]; then
        printf "  Deleting $topic... "
        
        if $KAFKA_BIN/kafka-topics.sh --bootstrap-server $KAFKA_BOOTSTRAP \
            --delete --topic "$topic" 2>/dev/null; then
            echo "DELETED"
            DELETED=$((DELETED + 1))
        else
            echo "SKIPPED (not found)"
            SKIPPED=$((SKIPPED + 1))
        fi
    fi
done

echo ""

# =========================================================================
# Step 3: Clean RocksDB state stores
# =========================================================================
echo "Step 3: Cleaning RocksDB state stores..."

ROCKSDB_PATH="/tmp/kafka-streams/streamingcandle"
if [ -d "$ROCKSDB_PATH" ]; then
    printf "  Removing $ROCKSDB_PATH... "
    if rm -rf "$ROCKSDB_PATH" 2>/dev/null; then
        echo "OK"
    else
        echo "FAILED (may need sudo)"
    fi
else
    echo "  $ROCKSDB_PATH does not exist, skipping"
fi

echo ""
echo "============================================="
echo "  Deletion Complete"
echo "============================================="
echo ""
echo "To recreate topics, run:"
echo "  bash create_family_topics.sh"
echo ""
