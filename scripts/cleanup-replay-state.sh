#!/bin/bash
# ============================================================================
# cleanup-replay-state.sh - Clean state stores before replay
# ============================================================================
# Run this script before starting a replay session to ensure fresh state
#
# Usage: ./scripts/cleanup-replay-state.sh [state-dir]
#
# Default state-dir: /tmp/kafka-streams-replay/streamingcandle
# ============================================================================

set -e

STATE_DIR="${1:-/tmp/kafka-streams-replay/streamingcandle}"
MONGODB_DB="${2:-tradeIngestion}"

echo "============================================================================"
echo "REPLAY STATE CLEANUP"
echo "============================================================================"
echo "State Directory: $STATE_DIR"
echo "MongoDB Database: $MONGODB_DB"
echo ""

# 1. Clean Kafka Streams state directory
echo "[1/4] Cleaning Kafka Streams state directory..."
if [ -d "$STATE_DIR" ]; then
    rm -rf "$STATE_DIR"
    echo "      Removed: $STATE_DIR"
else
    echo "      Directory doesn't exist, skipping"
fi

# Create fresh directory
mkdir -p "$STATE_DIR"
echo "      Created fresh directory: $STATE_DIR"

# 2. Clean RocksDB lock files (if any remain)
echo ""
echo "[2/4] Cleaning any stale lock files..."
find /tmp -name "*.lock" -path "*kafka-streams*" -delete 2>/dev/null || true
echo "      Done"

# 3. Optional: Clean MongoDB replay collections
echo ""
echo "[3/4] Cleaning MongoDB replay collections..."
if command -v mongosh &> /dev/null; then
    mongosh --quiet "$MONGODB_DB" --eval '
        // Clean signal outcomes from replay (marked with replay metadata)
        db.signal_outcomes.deleteMany({"metadata.isReplay": true});

        // Clean pattern outcomes from replay
        db.pattern_outcomes.deleteMany({"metadata.isReplay": true});

        // Print counts
        print("Cleaned replay data from MongoDB");
    ' 2>/dev/null || echo "      MongoDB cleanup skipped (connection failed or mongosh not available)"
else
    echo "      mongosh not found, skipping MongoDB cleanup"
fi

# 4. Reset consumer group offsets (optional - requires kafka tools)
echo ""
echo "[4/4] Consumer group offset info..."
if command -v kafka-consumer-groups.sh &> /dev/null; then
    echo "      Use kafka-consumer-groups.sh to reset offsets if needed:"
    echo "      kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group <group-id> --reset-offsets --to-earliest --execute --all-topics"
else
    echo "      Note: Using new consumer group IDs in replay profile will auto-reset to earliest"
fi

echo ""
echo "============================================================================"
echo "CLEANUP COMPLETE"
echo "============================================================================"
echo ""
echo "Ready for replay. Start with:"
echo "  java -jar target/streamingcandle.jar --spring.profiles.active=replay"
echo ""
echo "Or with Maven:"
echo "  mvn spring-boot:run -Dspring-boot.run.profiles=replay"
echo ""
echo "To change consumer group suffix (for fresh replay), edit:"
echo "  src/main/resources/application-replay.properties"
echo "  Update: kafka.streams.app-id-prefix=replay-YYYYMMDD-HHMM-"
echo "============================================================================"
