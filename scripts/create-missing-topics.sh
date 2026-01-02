#!/bin/bash

# ============================================================================
# Create Missing Kafka Topics Causing Rebalance Loop
# ============================================================================

KAFKA_BROKER="${KAFKA_BROKER:-13.203.60.173:9094}"
REPLICATION_FACTOR="${REPLICATION_FACTOR:-1}"
DEFAULT_PARTITIONS=20  # Match our doubled partition count

echo "======================================"
echo "Creating Missing Kafka Topics"
echo "Broker: $KAFKA_BROKER"
echo "Default Partitions: $DEFAULT_PARTITIONS"
echo "======================================"
echo ""

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

create_topic() {
    local topic_name=$1
    local partitions=$2

    echo -e "${YELLOW}Creating topic: $topic_name (${partitions} partitions)${NC}"

    if kafka-topics.sh --bootstrap-server "$KAFKA_BROKER" --describe --topic "$topic_name" &>/dev/null; then
        echo -e "${GREEN}  ✓ Topic already exists${NC}"
    else
        kafka-topics.sh --bootstrap-server "$KAFKA_BROKER" \
            --create --topic "$topic_name" \
            --partitions "$partitions" \
            --replication-factor "$REPLICATION_FACTOR" \
            --config retention.ms=86400000 \
            --config compression.type=lz4

        if [ $? -eq 0 ]; then
            echo -e "${GREEN}  ✓ Successfully created topic${NC}"
        else
            echo -e "${RED}  ✗ Failed to create topic${NC}"
            return 1
        fi
    fi
    echo ""
}

echo "=== Strategy Module Output Topics ==="

# VCP (Volume Cluster Profile) topics
create_topic "vcp-output-5m" 20
create_topic "vcp-output-15m" 20
create_topic "vcp-output-30m" 20
create_topic "vcp-combined" 20

# IPU (Institutional Participation & Urgency) topics
create_topic "ipu-signals-5m" 20
create_topic "ipu-signals-15m" 20
create_topic "ipu-signals-30m" 20

# Regime topics
create_topic "regime-security-output" 20
create_topic "regime-acl-output" 20
create_topic "regime-index-output" 20

# VTD (Volume Time Divergence) topics
create_topic "vtd-output" 20

# Additional family candle topics (if missing)
create_topic "family-candle-2m" 20
create_topic "family-candle-3m" 20
create_topic "family-candle-5m" 20
create_topic "family-candle-15m" 20
create_topic "family-candle-30m" 20
create_topic "family-candle-1h" 20
create_topic "family-candle-2h" 20
create_topic "family-candle-4h" 20
create_topic "family-candle-1d" 20

echo "======================================"
echo -e "${GREEN}Topic creation complete!${NC}"
echo "======================================"
echo ""

# Verify creation
echo "=== Verifying Topic Creation ==="
kafka-topics.sh --bootstrap-server "$KAFKA_BROKER" --list | grep -E "vcp-|ipu-|regime-|vtd-|family-candle-" | while read topic; do
    partitions=$(kafka-topics.sh --bootstrap-server "$KAFKA_BROKER" --describe --topic "$topic" 2>/dev/null | grep "PartitionCount" | awk '{print $4}')
    echo "  ✓ $topic: $partitions partitions"
done

echo ""
echo -e "${GREEN}Done! Restart your application now.${NC}"
