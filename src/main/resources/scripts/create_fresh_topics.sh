#!/bin/bash
# =============================================================================
# create_fresh_topics.sh
# Create fresh Kafka topics for streamingcandle and cleanup RocksDB state stores
# Also resets consumer group offsets to earliest for playback
# 
# IMPORTANT: Run with bash, not sh:  bash create_fresh_topics.sh
# Usage: bash create_fresh_topics.sh [--skip-rocksdb]
# =============================================================================

# Configuration
KAFKA_BOOTSTRAP="13.203.60.173:9094"
KAFKA_BIN="/opt/kafka/bin"  # Adjust if kafka is installed elsewhere
PARTITIONS=1
REPLICATION_FACTOR=1

# RocksDB state store locations (newline separated)
ROCKSDB_PATHS="/tmp/kafka-streams/streamingcandle
/var/lib/kafka-streams/streamingcandle"

# Kafka Streams application IDs (consumer groups to reset)
APP_IDS="unified-market-processor
unified-market-processor-prod1
prod-unified-ohlcv-1m
prod-unified-2m-2m
prod-unified-3m-3m
prod-unified-5m-5m
prod-unified-15m-15m
prod-unified-30m-30m
prod-unified-2h-120m
prod-unified-1d-375m
prod-unified-orderbook-1m
prod-unified-orderbook-2m
prod-unified-orderbook-3m
prod-unified-orderbook-5m
prod-unified-orderbook-15m
prod-unified-orderbook-30m
prod-unified-oi-1m
prod-unified-oi-2m
prod-unified-oi-3m
prod-unified-oi-5m
prod-unified-oi-15m
prod-unified-oi-30m
prod-unified-candle-processor
prod-vcp-5m
prod-vcp-15m
prod-vcp-30m
prod-ipu-5m
prod-ipu-15m
prod-ipu-30m
trading-signal-processor
regime-index-processor
regime-security-processor
regime-acl-processor
unified-signal-processor
fma-processor
curated-signal-processor"

# Topics to create (all derived topics)
TOPICS="candle-ohlcv-1m
candle-ohlcv-2m
candle-ohlcv-3m
candle-ohlcv-5m
candle-ohlcv-15m
candle-ohlcv-30m
candle-ohlcv-2h
candle-ohlcv-1d
orderbook-signals-1m
orderbook-signals-2m
orderbook-signals-3m
orderbook-signals-5m
orderbook-signals-15m
orderbook-signals-30m
oi-metrics-1m
oi-metrics-2m
oi-metrics-3m
oi-metrics-5m
oi-metrics-15m
oi-metrics-30m
unified-candle-1m
unified-candle-2m
unified-candle-3m
unified-candle-5m
unified-candle-15m
unified-candle-30m
vcp-signals-5m
vcp-signals-15m
vcp-signals-30m
vcp-combined
ipu-signals-5m
ipu-signals-15m
ipu-signals-30m
regime-index-output
regime-security-output
regime-acl-output
trading-signals
trading-signals-curated
css-output
som-output
vtd-output
ohm-output
magnitude-final
fudkii-output
watchlist-ranked"

SKIP_ROCKSDB=false
if [ "$1" = "--skip-rocksdb" ]; then
    SKIP_ROCKSDB=true
    echo "=== Skipping RocksDB cleanup ==="
fi

echo "============================================="
echo "  Kafka Fresh Topic Creation Script"
echo "============================================="
echo ""
echo "Kafka Bootstrap: $KAFKA_BOOTSTRAP"
echo "Partitions: $PARTITIONS"
echo "Replication Factor: $REPLICATION_FACTOR"
echo ""

# =========================================================================
# Step 1: Clean up RocksDB state stores
# =========================================================================
if [ "$SKIP_ROCKSDB" = false ]; then
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
else
    echo "Step 1: Skipped RocksDB cleanup"
fi

echo ""

# =========================================================================
# Step 2: Reset consumer group offsets to earliest
# =========================================================================
echo "Step 2: Resetting consumer group offsets to earliest..."

echo "$APP_IDS" | while read app_id; do
    if [ -n "$app_id" ]; then
        printf "  Resetting $app_id... "
        # Try to reset offset, but don't fail if group doesn't exist
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
# Step 3: Create fresh topics
# =========================================================================
echo "Step 3: Creating fresh topics..."

CREATED=0
EXISTED=0
FAILED=0

echo "$TOPICS" | while read topic; do
    if [ -n "$topic" ]; then
        printf "  Creating $topic... "
        
        # Check if topic exists
        if $KAFKA_BIN/kafka-topics.sh --bootstrap-server $KAFKA_BOOTSTRAP --describe --topic "$topic" 2>/dev/null | grep -q "Topic: $topic"; then
            echo "EXISTS"
        else
            # Create topic
            if $KAFKA_BIN/kafka-topics.sh --bootstrap-server $KAFKA_BOOTSTRAP \
                --create --topic "$topic" \
                --partitions $PARTITIONS \
                --replication-factor $REPLICATION_FACTOR 2>/dev/null; then
                echo "CREATED"
            else
                echo "FAILED"
            fi
        fi
    fi
done

echo ""
echo "============================================="
echo "  Topic Creation Complete"
echo "============================================="
echo ""
echo "Next Steps:"
echo "  1. Start streamingcandle with: java -jar streamingcandle.jar --spring.profiles.active=prod"
echo "  2. Monitor signals: $KAFKA_BIN/kafka-console-consumer.sh --bootstrap-server $KAFKA_BOOTSTRAP --topic trading-signals --from-beginning"
echo ""
