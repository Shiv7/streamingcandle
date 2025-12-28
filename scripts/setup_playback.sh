#!/bin/bash
# Setup Playback Testing - Reset Consumer Groups & Create Topics
# Run this before starting playback to ensure clean state

set -e  # Exit on error

KAFKA_DIR=${KAFKA_DIR:-~/kafka}  # Set your Kafka directory
BOOTSTRAP_SERVER=${BOOTSTRAP_SERVER:-localhost:9092}

echo "=========================================="
echo "Playback Testing Setup"
echo "=========================================="
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Step 1: List existing topics
echo -e "${YELLOW}📋 Step 1: Checking existing topics...${NC}"
$KAFKA_DIR/bin/kafka-topics.sh --bootstrap-server $BOOTSTRAP_SERVER --list
echo ""

# Step 2: Delete consumer groups (fresh start)
echo -e "${YELLOW}🗑️  Step 2: Resetting consumer groups...${NC}"

CONSUMER_GROUPS=(
    "trading-signal-processor"
    "curated-signal-processor"
    "curated-signal-processor-v1"
    "regime-processor"
    "vcp-processor"
    "ipu-processor"
    "css-processor"
    "magnitude-processor"
)

for group in "${CONSUMER_GROUPS[@]}"; do
    echo -e "   Deleting consumer group: ${group}"
    $KAFKA_DIR/bin/kafka-consumer-groups.sh --bootstrap-server $BOOTSTRAP_SERVER \
        --delete --group $group 2>/dev/null || echo "   (Group $group not found, skipping)"
done
echo -e "${GREEN}✅ Consumer groups reset${NC}"
echo ""

# Step 3: Create required topics (if not exist)
echo -e "${YELLOW}📝 Step 3: Creating required topics...${NC}"

TOPICS=(
    "trading-signals:3:1"
    "trading-signals-curated:3:1"
    "unified-candle-1m:3:1"
    "unified-candle-2m:3:1"
    "unified-candle-3m:3:1"
    "unified-candle-5m:3:1"
    "regime-index-output:3:1"
    "regime-security-output:3:1"
    "regime-acl-output:3:1"
    "vcp-combined:3:1"
    "css-output:3:1"
    "ipu-signals-5m:3:1"
    "ipu-signals-15m:3:1"
    "ipu-signals-30m:3:1"
    "magnitude-final:3:1"
)

for topic_config in "${TOPICS[@]}"; do
    IFS=':' read -r topic partitions replication <<< "$topic_config"
    echo -e "   Creating topic: ${topic}"
    $KAFKA_DIR/bin/kafka-topics.sh --bootstrap-server $BOOTSTRAP_SERVER \
        --create --if-not-exists \
        --topic $topic \
        --partitions $partitions \
        --replication-factor $replication 2>/dev/null || echo "   (Topic $topic already exists)"
done
echo -e "${GREEN}✅ Topics created${NC}"
echo ""

# Step 4: Verify topics
echo -e "${YELLOW}📊 Step 4: Verifying topic offsets...${NC}"
$KAFKA_DIR/bin/kafka-topics.sh --bootstrap-server $BOOTSTRAP_SERVER --list
echo ""

# Step 5: Clear application logs
echo -e "${YELLOW}🧹 Step 5: Clearing old logs...${NC}"
mkdir -p logs
rm -f logs/playback-comparison.log
rm -f logs/old-signals.log
rm -f logs/new-signals.log
echo -e "${GREEN}✅ Logs cleared${NC}"
echo ""

# Step 6: Instructions
echo -e "${GREEN}=========================================="
echo -e "✅ Playback Setup Complete!"
echo -e "==========================================${NC}"
echo ""
echo -e "${YELLOW}Next steps:${NC}"
echo ""
echo -e "1️⃣  Start Kafka (if not running):"
echo -e "   cd $KAFKA_DIR"
echo -e "   bin/kafka-server-start.sh config/server.properties"
echo ""
echo -e "2️⃣  Start signal monitors (in separate terminals):"
echo -e "   ${GREEN}# Terminal 1: OLD signals${NC}"
echo -e "   $KAFKA_DIR/bin/kafka-console-consumer.sh \\"
echo -e "     --bootstrap-server $BOOTSTRAP_SERVER \\"
echo -e "     --topic trading-signals \\"
echo -e "     --from-beginning \\"
echo -e "     --property print.timestamp=true | tee logs/old-signals.log"
echo ""
echo -e "   ${GREEN}# Terminal 2: NEW signals${NC}"
echo -e "   $KAFKA_DIR/bin/kafka-console-consumer.sh \\"
echo -e "     --bootstrap-server $BOOTSTRAP_SERVER \\"
echo -e "     --topic trading-signals-curated \\"
echo -e "     --from-beginning \\"
echo -e "     --property print.timestamp=true | tee logs/new-signals.log"
echo ""
echo -e "3️⃣  Start application in playback mode:"
echo -e "   ${GREEN}mvn clean package -DskipTests${NC}"
echo -e "   ${GREEN}java -jar target/streamingcandle-*.jar --spring.profiles.active=playback${NC}"
echo ""
echo -e "4️⃣  Monitor application logs:"
echo -e "   ${GREEN}tail -f logs/playback-comparison.log${NC}"
echo ""
echo -e "5️⃣  After playback completes, run comparison:"
echo -e "   ${GREEN}python3 scripts/compare_signals.py logs/playback-comparison.log${NC}"
echo ""
echo -e "${YELLOW}Note: Make sure your forward-testing-data topic has historical data!${NC}"
echo ""
