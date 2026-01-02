#!/bin/bash

# ============================================================================
# Create Kafka Topics with DOUBLED Partitions
# ============================================================================
# This script creates topics with 2x the original partition count
# Original: forwardtesting-data=5, OpenInterest=3, Orderbook=10
# New:      forwardtesting-data=10, OpenInterest=6, Orderbook=20

KAFKA_BROKER="${KAFKA_BROKER:-13.203.60.173:9094}"
REPLICATION_FACTOR="${REPLICATION_FACTOR:-1}"

echo "======================================"
echo "Creating Kafka Topics (Doubled Partitions)"
echo "Broker: $KAFKA_BROKER"
echo "Replication Factor: $REPLICATION_FACTOR"
echo "======================================"
echo ""

# Color codes for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Function to create or update topic
create_topic() {
    local topic_name=$1
    local partitions=$2

    echo -e "${YELLOW}Processing topic: $topic_name (${partitions} partitions)${NC}"

    # Check if topic exists
    if kafka-topics.sh --bootstrap-server "$KAFKA_BROKER" --describe --topic "$topic_name" &>/dev/null; then
        echo -e "${YELLOW}  Topic exists. Checking partition count...${NC}"

        current_partitions=$(kafka-topics.sh --bootstrap-server "$KAFKA_BROKER" \
            --describe --topic "$topic_name" | grep "PartitionCount" | awk '{print $4}')

        if [ "$current_partitions" -lt "$partitions" ]; then
            echo -e "${YELLOW}  Increasing partitions from $current_partitions to $partitions${NC}"
            kafka-topics.sh --bootstrap-server "$KAFKA_BROKER" \
                --alter --topic "$topic_name" \
                --partitions "$partitions"

            if [ $? -eq 0 ]; then
                echo -e "${GREEN}  ✓ Successfully increased partitions${NC}"
            else
                echo -e "${RED}  ✗ Failed to increase partitions${NC}"
                return 1
            fi
        else
            echo -e "${GREEN}  ✓ Topic already has $current_partitions partitions (>= $partitions)${NC}"
        fi
    else
        echo -e "${YELLOW}  Creating new topic...${NC}"
        kafka-topics.sh --bootstrap-server "$KAFKA_BROKER" \
            --create --topic "$topic_name" \
            --partitions "$partitions" \
            --replication-factor "$REPLICATION_FACTOR" \
            --config retention.ms=86400000 \
            --config segment.ms=3600000 \
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

# Create/update main input topics with DOUBLED partitions
echo "=== Main Input Topics ==="
create_topic "forwardtesting-data" 10      # Was 5, now 10
create_topic "OpenInterest" 6              # Was 3, now 6
create_topic "Orderbook" 20                # Was 10, now 20

# Create/update output topics
echo "=== Output Topics ==="
create_topic "instrument-candle-1m" 20     # Match max input (Orderbook=20)
create_topic "family-candle-1m" 20
create_topic "family-candle-5m" 20
create_topic "family-candle-15m" 20
create_topic "family-candle-30m" 20
create_topic "trading-signals" 20
create_topic "curated-trading-signals" 20

echo "======================================"
echo -e "${GREEN}Topic creation/update complete!${NC}"
echo "======================================"
echo ""

# Show final topic list
echo "=== Final Topic Configuration ==="
kafka-topics.sh --bootstrap-server "$KAFKA_BROKER" --list | grep -E "forwardtesting|OpenInterest|Orderbook|candle|signal" | while read topic; do
    partitions=$(kafka-topics.sh --bootstrap-server "$KAFKA_BROKER" --describe --topic "$topic" 2>/dev/null | grep "PartitionCount" | awk '{print $4}')
    echo "  $topic: $partitions partitions"
done
