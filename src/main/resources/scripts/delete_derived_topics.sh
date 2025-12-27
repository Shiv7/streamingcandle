#!/bin/bash
# =============================================================================
# delete_derived_topics.sh
# Delete all derived Kafka topics, preserving raw data topics
# 
# Usage: ./delete_derived_topics.sh [--dry-run]
# =============================================================================

set -e

# Configuration
KAFKA_BOOTSTRAP="13.203.60.173:9094"
KAFKA_BIN="/opt/kafka/bin"  # Adjust if kafka is installed elsewhere

# Raw data topics to PRESERVE (never delete these)
PRESERVE_TOPICS=(
    "forwardtesting-data"
    "OpenInterest"
    "Orderbook"
)

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

DRY_RUN=false
if [[ "$1" == "--dry-run" ]]; then
    DRY_RUN=true
    echo -e "${YELLOW}=== DRY RUN MODE - No topics will be deleted ===${NC}"
fi

echo -e "${BLUE}=============================================${NC}"
echo -e "${BLUE}  Kafka Derived Topic Deletion Script${NC}"
echo -e "${BLUE}=============================================${NC}"
echo ""
echo -e "Kafka Bootstrap: ${GREEN}$KAFKA_BOOTSTRAP${NC}"
echo -e "Preserved Topics: ${GREEN}${PRESERVE_TOPICS[*]}${NC}"
echo ""

# Function to check if topic should be preserved
should_preserve() {
    local topic=$1
    for preserve in "${PRESERVE_TOPICS[@]}"; do
        if [[ "$topic" == "$preserve" ]]; then
            return 0  # true - should preserve
        fi
    done
    return 1  # false - can delete
}

# Get list of all topics
echo -e "${BLUE}Fetching topic list...${NC}"
ALL_TOPICS=$($KAFKA_BIN/kafka-topics.sh --bootstrap-server $KAFKA_BOOTSTRAP --list 2>/dev/null)

if [[ -z "$ALL_TOPICS" ]]; then
    echo -e "${RED}ERROR: Could not fetch topics. Check Kafka connectivity.${NC}"
    exit 1
fi

# Separate topics into preserve and delete lists
TOPICS_TO_DELETE=()
TOPICS_TO_PRESERVE=()

while IFS= read -r topic; do
    # Skip empty lines and internal Kafka topics
    if [[ -z "$topic" ]] || [[ "$topic" == __* ]]; then
        continue
    fi
    
    if should_preserve "$topic"; then
        TOPICS_TO_PRESERVE+=("$topic")
    else
        TOPICS_TO_DELETE+=("$topic")
    fi
done <<< "$ALL_TOPICS"

echo ""
echo -e "${GREEN}=== Topics to PRESERVE (${#TOPICS_TO_PRESERVE[@]}) ===${NC}"
for topic in "${TOPICS_TO_PRESERVE[@]}"; do
    echo -e "  ✅ $topic"
done

echo ""
echo -e "${RED}=== Topics to DELETE (${#TOPICS_TO_DELETE[@]}) ===${NC}"
for topic in "${TOPICS_TO_DELETE[@]}"; do
    echo -e "  ❌ $topic"
done

if [[ ${#TOPICS_TO_DELETE[@]} -eq 0 ]]; then
    echo ""
    echo -e "${GREEN}No topics to delete. Exiting.${NC}"
    exit 0
fi

if [[ "$DRY_RUN" == true ]]; then
    echo ""
    echo -e "${YELLOW}DRY RUN complete. Run without --dry-run to delete topics.${NC}"
    exit 0
fi

# Confirmation
echo ""
echo -e "${RED}⚠️  WARNING: This will delete ${#TOPICS_TO_DELETE[@]} topics!${NC}"
read -p "Type 'DELETE' to confirm: " confirmation

if [[ "$confirmation" != "DELETE" ]]; then
    echo -e "${YELLOW}Aborted. No topics deleted.${NC}"
    exit 0
fi

# Delete topics
echo ""
echo -e "${BLUE}Deleting topics...${NC}"

FAILED=0
SUCCESS=0

for topic in "${TOPICS_TO_DELETE[@]}"; do
    echo -n "  Deleting $topic... "
    if $KAFKA_BIN/kafka-topics.sh --bootstrap-server $KAFKA_BOOTSTRAP --delete --topic "$topic" 2>/dev/null; then
        echo -e "${GREEN}OK${NC}"
        ((SUCCESS++))
    else
        echo -e "${RED}FAILED${NC}"
        ((FAILED++))
    fi
done

echo ""
echo -e "${GREEN}=============================================${NC}"
echo -e "${GREEN}  Deletion Complete${NC}"
echo -e "${GREEN}  Success: $SUCCESS | Failed: $FAILED${NC}"
echo -e "${GREEN}=============================================${NC}"
