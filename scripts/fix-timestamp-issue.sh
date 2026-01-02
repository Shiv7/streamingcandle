#!/bin/bash

# ==================================================================
# Fix Timestamp Issue - Complete Cleanup and Redeployment Script
# ==================================================================
# This script:
# 1. Stops the application
# 2. Deletes all repartition and changelog topics (they'll be recreated)
# 3. Clears Kafka Streams state
# 4. Restarts the application with timestamp fixes

set -e  # Exit on error

KAFKA_BROKER="${KAFKA_BROKER:-localhost:9094}"
APP_PREFIX="${APP_PREFIX:-replay-20251230-v2-}"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'  # No Color

echo "=========================================="
echo -e "${YELLOW}Timestamp Issue Fix - Cleanup and Redeploy${NC}"
echo "=========================================="
echo ""

# ==================================================================
# Step 1: Stop the application
# ==================================================================
echo -e "${YELLOW}Step 1: Stopping application...${NC}"
pkill -f streamingcandle || true
sleep 3

# Verify it's stopped
if ps aux | grep -v grep | grep streamingcandle > /dev/null; then
    echo -e "${RED}ERROR: Application still running. Force killing...${NC}"
    pkill -9 -f streamingcandle || true
    sleep 2
fi

echo -e "${GREEN}✓ Application stopped${NC}"
echo ""

# ==================================================================
# Step 2: Delete repartition and changelog topics
# ==================================================================
echo -e "${YELLOW}Step 2: Deleting internal Kafka topics...${NC}"
echo "These will be recreated with correct timestamp configuration"
echo ""

# Function to delete topics matching pattern
delete_topics() {
    local pattern=$1
    local count=0

    for topic in $(kafka-topics.sh --bootstrap-server "$KAFKA_BROKER" --list 2>/dev/null | grep "$pattern" || true); do
        echo -e "  Deleting: $topic"
        kafka-topics.sh --bootstrap-server "$KAFKA_BROKER" --delete --topic "$topic" 2>/dev/null || true
        ((count++))
    done

    echo "$count"
}

echo -e "${YELLOW}Deleting repartition topics...${NC}"
repartition_count=$(delete_topics "${APP_PREFIX}.*-repartition")
echo -e "${GREEN}✓ Deleted $repartition_count repartition topics${NC}"
echo ""

echo -e "${YELLOW}Deleting changelog topics...${NC}"
changelog_count=$(delete_topics "${APP_PREFIX}.*-changelog")
echo -e "${GREEN}✓ Deleted $changelog_count changelog topics${NC}"
echo ""

# Wait for deletions to complete
echo "Waiting for topic deletions to complete..."
sleep 5

# ==================================================================
# Step 3: Clear Kafka Streams state directory
# ==================================================================
echo -e "${YELLOW}Step 3: Clearing Kafka Streams state...${NC}"

STATE_DIRS=(
    "/tmp/kafka-streams/streamingcandle"
    "/var/lib/kafka-streams/streamingcandle"
)

for dir in "${STATE_DIRS[@]}"; do
    if [ -d "$dir" ]; then
        echo "  Clearing: $dir"
        rm -rf "$dir"/*
        echo -e "${GREEN}✓ Cleared $dir${NC}"
    fi
done

echo ""

# ==================================================================
# Step 4: Check system clock synchronization
# ==================================================================
echo -e "${YELLOW}Step 4: Checking system clock synchronization...${NC}"

current_time=$(date +%s)
echo "Current server time: $(date)"

if command -v timedatectl &> /dev/null; then
    echo ""
    timedatectl status | grep -E "synchronized|NTP"
fi

echo ""

# ==================================================================
# Step 5: Display Kafka broker timestamp settings
# ==================================================================
echo -e "${YELLOW}Step 5: Kafka broker timestamp settings...${NC}"
echo "NOTE: Broker max.message.time.difference.ms should be >= 3600000 (1 hour)"
echo "If you see timestamp issues, increase this value in broker's server.properties"
echo ""

# ==================================================================
# Step 6: Ready to start
# ==================================================================
echo "=========================================="
echo -e "${GREEN}Cleanup complete!${NC}"
echo "=========================================="
echo ""
echo "Summary:"
echo "  ✓ Application stopped"
echo "  ✓ Deleted $repartition_count repartition topics"
echo "  ✓ Deleted $changelog_count changelog topics"
echo "  ✓ Cleared state directories"
echo ""
echo -e "${YELLOW}Next steps:${NC}"
echo "1. Upload new JAR file:"
echo "   ${GREEN}scp -i ~/Downloads/newDevinaKey.pem target/demo-0.0.1-SNAPSHOT.jar ubuntu@13.203.60.173:/home/ubuntu/streamingcandle/${NC}"
echo ""
echo "2. Start the application:"
echo "   ${GREEN}nohup java -jar demo-0.0.1-SNAPSHOT.jar > nohup.out 2>&1 &${NC}"
echo ""
echo "3. Monitor startup:"
echo "   ${GREEN}tail -f nohup.out${NC}"
echo ""
echo "4. Watch for timestamp warnings (should see clamping if timestamps are future):"
echo "   ${GREEN}tail -f nohup.out | grep -E 'Clamping to prevent InvalidTimestampException'${NC}"
echo ""
echo "5. Verify no more InvalidTimestampException:"
echo "   ${GREEN}tail -f nohup.out | grep -E 'InvalidTimestampException'${NC} (should be empty)"
echo ""
echo "6. Check consumer group status:"
echo "   ${GREEN}kafka-consumer-groups.sh --bootstrap-server $KAFKA_BROKER --describe --group ${APP_PREFIX}unified-instrument-candle-processor${NC}"
echo ""
echo "=========================================="
echo -e "${GREEN}Ready to deploy updated application!${NC}"
echo "=========================================="
