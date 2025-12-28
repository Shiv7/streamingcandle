#!/bin/bash
# =============================================================================
# STREAMINGCANDLE - CLEAN SETUP SCRIPT
# =============================================================================
# IMPORTANT: Run with bash, not sh:  bash clean_setup.sh
# =============================================================================

set -e

# Configuration
KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:-13.203.60.173:9094}"
STATE_DIR="${KAFKA_STATE_DIR:-/tmp/kafka-streams/streamingcandle}"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { printf "${GREEN}[INFO]${NC} %s\n" "$1"; }
log_warn() { printf "${YELLOW}[WARN]${NC} %s\n" "$1"; }
log_error() { printf "${RED}[ERROR]${NC} %s\n" "$1"; }
log_step() { printf "\n${BLUE}=== %s ===${NC}\n" "$1"; }

# =============================================================================
# STEP 1: Clear Local State Stores
# =============================================================================
log_step "Step 1: Clearing Local State Stores"

if [ -d "$STATE_DIR" ]; then
    log_info "Deleting state directory: $STATE_DIR"
    rm -rf "$STATE_DIR"/*
    log_info "Local state stores cleared"
else
    log_warn "State directory does not exist: $STATE_DIR"
fi

# =============================================================================
# STEP 2: Delete Stale Changelog Topics
# =============================================================================
log_step "Step 2: Deleting Stale Changelog Topics"

log_info "Fetching topic list from Kafka..."
TOPICS=$(kafka-topics.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --list 2>/dev/null || echo "")

if [ -z "$TOPICS" ]; then
    log_error "Failed to fetch topics from Kafka. Check connectivity."
else
    # Delete changelog topics
    echo "$TOPICS" | grep -E '.*-changelog$' | while read -r topic; do
        if [ -n "$topic" ]; then
            log_info "Deleting changelog: $topic"
            kafka-topics.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --delete --topic "$topic" 2>/dev/null || true
        fi
    done
    
    # Delete repartition topics
    echo "$TOPICS" | grep -E '.*-repartition$' | while read -r topic; do
        if [ -n "$topic" ]; then
            log_info "Deleting repartition: $topic"
            kafka-topics.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --delete --topic "$topic" 2>/dev/null || true
        fi
    done
    
    log_info "Stale internal topics deleted"
fi

# =============================================================================
# STEP 3: Delete Legacy Topics
# =============================================================================
log_step "Step 3: Deleting Legacy Topics"

LEGACY_TOPICS="candle-ohlcv-1m candle-ohlcv-2m candle-ohlcv-3m candle-ohlcv-5m candle-ohlcv-15m candle-ohlcv-30m candle-ohlcv-2h candle-ohlcv-1d unified-candle-1m unified-candle-2m unified-candle-3m unified-candle-5m unified-candle-15m unified-candle-30m orderbook-signals-1m orderbook-signals-2m orderbook-signals-3m orderbook-signals-5m orderbook-signals-15m orderbook-signals-30m oi-metrics-1m oi-metrics-2m oi-metrics-3m oi-metrics-5m oi-metrics-15m oi-metrics-30m"

for topic in $LEGACY_TOPICS; do
    if echo "$TOPICS" | grep -q "^${topic}$"; then
        log_info "Deleting legacy: $topic"
        kafka-topics.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --delete --topic "$topic" 2>/dev/null || true
    fi
done
log_info "Legacy topics deleted"

# =============================================================================
# STEP 4: Create Required Topics
# =============================================================================
log_step "Step 4: Creating Required Topics"

PARTITIONS=3
REPLICATION=1
RETENTION_MS=604800000

# Helper function
create_topic() {
    topic=$1
    if echo "$TOPICS" | grep -q "^${topic}$"; then
        log_warn "Topic exists: $topic"
    else
        log_info "Creating: $topic"
        kafka-topics.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --create --topic "$topic" --partitions $PARTITIONS --replication-factor $REPLICATION --config retention.ms=$RETENTION_MS 2>/dev/null || true
    fi
}

# Input topics
log_info "Creating input topics..."
create_topic "forwardtesting-data"
create_topic "Orderbook"
create_topic "OpenInterest"

# Family candle topics
log_info "Creating family candle topics..."
create_topic "instrument-candle-1m"
create_topic "family-candle-1m"
create_topic "family-candle-2m"
create_topic "family-candle-3m"
create_topic "family-candle-5m"
create_topic "family-candle-15m"
create_topic "family-candle-30m"
create_topic "family-candle-1h"
create_topic "family-candle-2h"
create_topic "family-candle-4h"
create_topic "family-candle-1d"

# Signal output topics
log_info "Creating signal topics..."
create_topic "ipu-signals-5m"
create_topic "ipu-signals-15m"
create_topic "ipu-signals-30m"
create_topic "vcp-signals-5m"
create_topic "vcp-signals-15m"
create_topic "vcp-signals-30m"
create_topic "regime-index-output"
create_topic "regime-security-output"
create_topic "regime-acl-output"
create_topic "trading-signals"
create_topic "curated-trading-signals"

log_info "All topics created"

# =============================================================================
# STEP 5: Reset Consumer Groups
# =============================================================================
log_step "Step 5: Resetting Consumer Groups"

GROUPS="unified-instrument-candle-processor family-candle-processor timeframe-aggregator ipu-processor vcp-processor regime-processor curated-signal-processor"

for group in $GROUPS; do
    kafka-consumer-groups.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --list 2>/dev/null | grep "$group" | while read -r cg; do
        if [ -n "$cg" ]; then
            log_info "Deleting consumer group: $cg"
            kafka-consumer-groups.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --delete --group "$cg" 2>/dev/null || true
        fi
    done
done
log_info "Consumer groups reset"

# =============================================================================
# SUMMARY
# =============================================================================
log_step "Setup Complete!"

printf "\nSummary:\n"
printf "  - Local state stores cleared: %s\n" "$STATE_DIR"
printf "  - Stale changelog topics deleted\n"
printf "  - Legacy topics deleted\n"
printf "  - Required topics created\n"
printf "  - Consumer groups reset\n"
printf "\nNext steps:\n"
printf "  1. Restart: mvn spring-boot:run\n"
printf "  2. Verify: kafka-console-consumer.sh --bootstrap-server %s --topic family-candle-1m\n\n" "$KAFKA_BOOTSTRAP"
