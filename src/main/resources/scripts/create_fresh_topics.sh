#!/bin/bash
# =============================================================================
# create_fresh_topics.sh
# Create fresh Kafka topics for streamingcandle and cleanup RocksDB state stores
# Also resets consumer group offsets to earliest for playback
# 
# Usage: ./create_fresh_topics.sh [--skip-rocksdb]
# =============================================================================

set -e

# Configuration
KAFKA_BOOTSTRAP="13.203.60.173:9094"
KAFKA_BIN="/opt/kafka/bin"  # Adjust if kafka is installed elsewhere
PARTITIONS=1
REPLICATION_FACTOR=1

# RocksDB state store locations
ROCKSDB_PATHS=(
    "/tmp/kafka-streams/streamingcandle"
    "/var/lib/kafka-streams/streamingcandle"
)

# Kafka Streams application IDs (consumer groups to reset)
APP_IDS=(
    "unified-market-processor"
    "unified-market-processor-prod1"
    "prod-unified-ohlcv-1m"
    "prod-unified-2m-2m"
    "prod-unified-3m-3m"
    "prod-unified-5m-5m"
    "prod-unified-15m-15m"
    "prod-unified-30m-30m"
    "prod-unified-2h-120m"
    "prod-unified-1d-375m"
    "prod-unified-orderbook-1m"
    "prod-unified-orderbook-2m"
    "prod-unified-orderbook-3m"
    "prod-unified-orderbook-5m"
    "prod-unified-orderbook-15m"
    "prod-unified-orderbook-30m"
    "prod-unified-oi-1m"
    "prod-unified-oi-2m"
    "prod-unified-oi-3m"
    "prod-unified-oi-5m"
    "prod-unified-oi-15m"
    "prod-unified-oi-30m"
    "prod-unified-candle-processor"
    "prod-vcp-5m"
    "prod-vcp-15m"
    "prod-vcp-30m"
    "prod-ipu-5m"
    "prod-ipu-15m"
    "prod-ipu-30m"
    "trading-signal-processor"
    "regime-index-processor"
    "regime-security-processor"
    "regime-acl-processor"
    "unified-signal-processor"
    "fma-processor"
    "curated-signal-processor"
)

# Topics to create (all derived topics)
TOPICS=(
    # Candle topics
    "candle-ohlcv-1m"
    "candle-ohlcv-2m"
    "candle-ohlcv-3m"
    "candle-ohlcv-5m"
    "candle-ohlcv-15m"
    "candle-ohlcv-30m"
    "candle-ohlcv-2h"
    "candle-ohlcv-1d"
    
    # Orderbook signals
    "orderbook-signals-1m"
    "orderbook-signals-2m"
    "orderbook-signals-3m"
    "orderbook-signals-5m"
    "orderbook-signals-15m"
    "orderbook-signals-30m"
    
    # OI metrics
    "oi-metrics-1m"
    "oi-metrics-2m"
    "oi-metrics-3m"
    "oi-metrics-5m"
    "oi-metrics-15m"
    "oi-metrics-30m"
    
    # Unified candles
    "unified-candle-1m"
    "unified-candle-2m"
    "unified-candle-3m"
    "unified-candle-5m"
    "unified-candle-15m"
    "unified-candle-30m"
    
    # VCP signals
    "vcp-signals-5m"
    "vcp-signals-15m"
    "vcp-signals-30m"
    "vcp-combined"
    
    # IPU signals
    "ipu-signals-5m"
    "ipu-signals-15m"
    "ipu-signals-30m"
    
    # Regime outputs
    "regime-index-output"
    "regime-security-output"
    "regime-acl-output"
    
    # Trading signals
    "trading-signals"
    "trading-signals-curated"
    
    # Module outputs
    "css-output"
    "som-output"
    "vtd-output"
    "ohm-output"
    "magnitude-final"
    "fudkii-output"
    "watchlist-ranked"
)

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

SKIP_ROCKSDB=false
if [[ "$1" == "--skip-rocksdb" ]]; then
    SKIP_ROCKSDB=true
    echo -e "${YELLOW}=== Skipping RocksDB cleanup ===${NC}"
fi

echo -e "${BLUE}=============================================${NC}"
echo -e "${BLUE}  Kafka Fresh Topic Creation Script${NC}"
echo -e "${BLUE}=============================================${NC}"
echo ""
echo -e "Kafka Bootstrap: ${GREEN}$KAFKA_BOOTSTRAP${NC}"
echo -e "Partitions: ${GREEN}$PARTITIONS${NC}"
echo -e "Replication Factor: ${GREEN}$REPLICATION_FACTOR${NC}"
echo ""

# =========================================================================
# Step 1: Clean up RocksDB state stores
# =========================================================================
if [[ "$SKIP_ROCKSDB" == false ]]; then
    echo -e "${BLUE}Step 1: Cleaning RocksDB state stores...${NC}"
    
    for path in "${ROCKSDB_PATHS[@]}"; do
        if [[ -d "$path" ]]; then
            echo -n "  Removing $path... "
            if rm -rf "$path"; then
                echo -e "${GREEN}OK${NC}"
            else
                echo -e "${YELLOW}FAILED (may need sudo)${NC}"
            fi
        else
            echo -e "  ${YELLOW}$path does not exist, skipping${NC}"
        fi
    done
else
    echo -e "${YELLOW}Step 1: Skipped RocksDB cleanup${NC}"
fi

echo ""

# =========================================================================
# Step 2: Reset consumer group offsets to earliest
# =========================================================================
echo -e "${BLUE}Step 2: Resetting consumer group offsets to earliest...${NC}"

for app_id in "${APP_IDS[@]}"; do
    echo -n "  Resetting $app_id... "
    # Try to reset offset, but don't fail if group doesn't exist
    if $KAFKA_BIN/kafka-consumer-groups.sh --bootstrap-server $KAFKA_BOOTSTRAP \
        --group "$app_id" --reset-offsets --to-earliest --all-topics --execute 2>/dev/null; then
        echo -e "${GREEN}OK${NC}"
    else
        echo -e "${YELLOW}SKIPPED (group may not exist)${NC}"
    fi
done

echo ""

# =========================================================================
# Step 3: Create fresh topics
# =========================================================================
echo -e "${BLUE}Step 3: Creating fresh topics...${NC}"

CREATED=0
EXISTED=0
FAILED=0

for topic in "${TOPICS[@]}"; do
    echo -n "  Creating $topic... "
    
    # Check if topic exists
    if $KAFKA_BIN/kafka-topics.sh --bootstrap-server $KAFKA_BOOTSTRAP --describe --topic "$topic" 2>/dev/null | grep -q "Topic: $topic"; then
        echo -e "${YELLOW}EXISTS${NC}"
        ((EXISTED++))
    else
        # Create topic
        if $KAFKA_BIN/kafka-topics.sh --bootstrap-server $KAFKA_BOOTSTRAP \
            --create --topic "$topic" \
            --partitions $PARTITIONS \
            --replication-factor $REPLICATION_FACTOR 2>/dev/null; then
            echo -e "${GREEN}CREATED${NC}"
            ((CREATED++))
        else
            echo -e "${RED}FAILED${NC}"
            ((FAILED++))
        fi
    fi
done

echo ""
echo -e "${GREEN}=============================================${NC}"
echo -e "${GREEN}  Topic Creation Complete${NC}"
echo -e "${GREEN}  Created: $CREATED | Already Existed: $EXISTED | Failed: $FAILED${NC}"
echo -e "${GREEN}=============================================${NC}"
echo ""
echo -e "${BLUE}Next Steps:${NC}"
echo -e "  1. Start streamingcandle with: ${GREEN}java -jar streamingcandle.jar --spring.profiles.active=prod${NC}"
echo -e "  2. Monitor signals on: ${GREEN}$KAFKA_BIN/kafka-console-consumer.sh --bootstrap-server $KAFKA_BOOTSTRAP --topic trading-signals --from-beginning${NC}"
echo ""
