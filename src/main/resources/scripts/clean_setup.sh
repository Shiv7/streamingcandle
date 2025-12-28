#!/bin/bash
# =============================================================================
# STREAMINGCANDLE - CLEAN SETUP SCRIPT
# =============================================================================
# This script performs a complete reset of the streaming candle system:
# 1. Clears local RocksDB state stores
# 2. Deletes all stale Kafka changelog topics
# 3. Creates required family candle topics
# 4. Resets consumer group offsets
#
# Usage: ./clean_setup.sh [OPTIONS]
#   --skip-topics     Skip topic creation (only cleanup)
#   --skip-state      Skip local state store cleanup
#   --dry-run         Show what would be done without executing
# =============================================================================

set -e

# Configuration
KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:-13.203.60.173:9094}"
STATE_DIR="${KAFKA_STATE_DIR:-/tmp/kafka-streams/streamingcandle}"
APP_NAME="streamingcandle"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Parse arguments
SKIP_TOPICS=false
SKIP_STATE=false
DRY_RUN=false

for arg in "$@"; do
    case $arg in
        --skip-topics) SKIP_TOPICS=true ;;
        --skip-state) SKIP_STATE=true ;;
        --dry-run) DRY_RUN=true ;;
        --help|-h)
            echo "Usage: $0 [--skip-topics] [--skip-state] [--dry-run]"
            exit 0
            ;;
    esac
done

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }
log_step() { echo -e "\n${BLUE}=== $1 ===${NC}"; }

run_cmd() {
    if [ "$DRY_RUN" = true ]; then
        echo "[DRY-RUN] $1"
    else
        eval "$1"
    fi
}

# =============================================================================
# STEP 1: Clear Local State Stores
# =============================================================================
if [ "$SKIP_STATE" = false ]; then
    log_step "Step 1: Clearing Local State Stores"
    
    if [ -d "$STATE_DIR" ]; then
        log_info "Deleting state directory: $STATE_DIR"
        run_cmd "rm -rf $STATE_DIR/*"
        log_info "✅ Local state stores cleared"
    else
        log_warn "State directory does not exist: $STATE_DIR"
    fi
else
    log_warn "Skipping local state store cleanup"
fi

# =============================================================================
# STEP 2: Delete Stale Changelog Topics
# =============================================================================
log_step "Step 2: Deleting Stale Changelog Topics"

# List of changelog topic patterns to delete
CHANGELOG_PATTERNS=(
    ".*-changelog$"
    ".*-repartition$"
)

log_info "Fetching topic list from Kafka..."
TOPICS=$(kafka-topics.sh --bootstrap-server $KAFKA_BOOTSTRAP --list 2>/dev/null || echo "")

if [ -z "$TOPICS" ]; then
    log_error "Failed to fetch topics from Kafka. Check connectivity."
else
    for pattern in "${CHANGELOG_PATTERNS[@]}"; do
        MATCHING_TOPICS=$(echo "$TOPICS" | grep -E "$pattern" || true)
        if [ -n "$MATCHING_TOPICS" ]; then
            while IFS= read -r topic; do
                if [ -n "$topic" ]; then
                    log_info "Deleting topic: $topic"
                    run_cmd "kafka-topics.sh --bootstrap-server $KAFKA_BOOTSTRAP --delete --topic $topic 2>/dev/null || true"
                fi
            done <<< "$MATCHING_TOPICS"
        fi
    done
    log_info "✅ Stale changelog topics deleted"
fi

# =============================================================================
# STEP 3: Delete Legacy Topics (No longer needed)
# =============================================================================
log_step "Step 3: Deleting Legacy Topics"

LEGACY_TOPICS=(
    # Legacy candle topics (replaced by family-candle-*)
    "candle-ohlcv-1m" "candle-ohlcv-2m" "candle-ohlcv-3m" "candle-ohlcv-5m"
    "candle-ohlcv-15m" "candle-ohlcv-30m" "candle-ohlcv-2h" "candle-ohlcv-1d"
    # Legacy unified candle topics (replaced by family-candle-*)
    "unified-candle-1m" "unified-candle-2m" "unified-candle-3m" "unified-candle-5m"
    "unified-candle-15m" "unified-candle-30m"
    # Legacy orderbook signal topics
    "orderbook-signals-1m" "orderbook-signals-2m" "orderbook-signals-3m"
    "orderbook-signals-5m" "orderbook-signals-15m" "orderbook-signals-30m"
    # Legacy OI metrics topics
    "oi-metrics-1m" "oi-metrics-2m" "oi-metrics-3m"
    "oi-metrics-5m" "oi-metrics-15m" "oi-metrics-30m"
)

for topic in "${LEGACY_TOPICS[@]}"; do
    if echo "$TOPICS" | grep -q "^${topic}$"; then
        log_info "Deleting legacy topic: $topic"
        run_cmd "kafka-topics.sh --bootstrap-server $KAFKA_BOOTSTRAP --delete --topic $topic 2>/dev/null || true"
    fi
done
log_info "✅ Legacy topics deleted"

# =============================================================================
# STEP 4: Create Required Topics
# =============================================================================
if [ "$SKIP_TOPICS" = false ]; then
    log_step "Step 4: Creating Required Topics"
    
    # Topic configuration
    PARTITIONS=3
    REPLICATION=1
    RETENTION_MS=604800000  # 7 days
    
    # Core input topics (should already exist, but create if missing)
    INPUT_TOPICS=(
        "forwardtesting-data"
        "Orderbook"
        "OpenInterest"
    )
    
    # Family candle topics (NEW architecture)
    FAMILY_CANDLE_TOPICS=(
        "instrument-candle-1m"
        "family-candle-1m"
        "family-candle-2m"
        "family-candle-3m"
        "family-candle-5m"
        "family-candle-15m"
        "family-candle-30m"
        "family-candle-1h"
        "family-candle-2h"
        "family-candle-4h"
        "family-candle-1d"
    )
    
    # Signal output topics
    SIGNAL_TOPICS=(
        "ipu-signals-5m"
        "ipu-signals-15m"
        "ipu-signals-30m"
        "vcp-signals-5m"
        "vcp-signals-15m"
        "vcp-signals-30m"
        "regime-index-output"
        "regime-security-output"
        "regime-acl-output"
        "trading-signals"
        "curated-trading-signals"
    )
    
    create_topic() {
        local topic=$1
        if echo "$TOPICS" | grep -q "^${topic}$"; then
            log_warn "Topic already exists: $topic"
        else
            log_info "Creating topic: $topic"
            run_cmd "kafka-topics.sh --bootstrap-server $KAFKA_BOOTSTRAP --create --topic $topic --partitions $PARTITIONS --replication-factor $REPLICATION --config retention.ms=$RETENTION_MS 2>/dev/null || true"
        fi
    }
    
    log_info "Creating input topics..."
    for topic in "${INPUT_TOPICS[@]}"; do
        create_topic "$topic"
    done
    
    log_info "Creating family candle topics..."
    for topic in "${FAMILY_CANDLE_TOPICS[@]}"; do
        create_topic "$topic"
    done
    
    log_info "Creating signal output topics..."
    for topic in "${SIGNAL_TOPICS[@]}"; do
        create_topic "$topic"
    done
    
    log_info "✅ All required topics created"
else
    log_warn "Skipping topic creation"
fi

# =============================================================================
# STEP 5: Reset Consumer Group Offsets
# =============================================================================
log_step "Step 5: Resetting Consumer Group Offsets"

CONSUMER_GROUPS=(
    "unified-instrument-candle-processor"
    "family-candle-processor"
    "timeframe-aggregator"
    "ipu-processor"
    "vcp-processor"
    "regime-processor"
    "curated-signal-processor"
)

for group in "${CONSUMER_GROUPS[@]}"; do
    # Find all related consumer groups
    MATCHING_GROUPS=$(kafka-consumer-groups.sh --bootstrap-server $KAFKA_BOOTSTRAP --list 2>/dev/null | grep "$group" || true)
    if [ -n "$MATCHING_GROUPS" ]; then
        while IFS= read -r cg; do
            if [ -n "$cg" ]; then
                log_info "Deleting consumer group: $cg"
                run_cmd "kafka-consumer-groups.sh --bootstrap-server $KAFKA_BOOTSTRAP --delete --group $cg 2>/dev/null || true"
            fi
        done <<< "$MATCHING_GROUPS"
    fi
done
log_info "✅ Consumer groups reset"

# =============================================================================
# SUMMARY
# =============================================================================
log_step "Setup Complete!"

echo ""
echo "Summary:"
echo "  ✅ Local state stores cleared: $STATE_DIR"
echo "  ✅ Stale changelog topics deleted"
echo "  ✅ Legacy topics deleted"
echo "  ✅ Required topics created"
echo "  ✅ Consumer groups reset"
echo ""
echo "Next steps:"
echo "  1. Restart the application: mvn spring-boot:run"
echo "  2. Check logs for: 'Started ConsumerApplication'"
echo "  3. Verify data flow: kafka-console-consumer.sh --bootstrap-server $KAFKA_BOOTSTRAP --topic family-candle-1m"
echo ""
