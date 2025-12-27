#!/bin/bash
# =============================================================================
# delete_derived_topics.sh
# Delete all derived Kafka topics, preserving raw data topics
# 
# IMPORTANT: Run with bash, not sh:  bash delete_derived_topics.sh
# Usage: bash delete_derived_topics.sh [--dry-run]
# =============================================================================

# Configuration
KAFKA_BOOTSTRAP="13.203.60.173:9094"
KAFKA_BIN="/opt/kafka/bin"  # Adjust if kafka is installed elsewhere

# Raw data topics to PRESERVE (never delete these) - pipe separated
PRESERVE_PATTERN="forwardtesting-data|OpenInterest|Orderbook"

DRY_RUN=false
if [ "$1" = "--dry-run" ]; then
    DRY_RUN=true
    echo "=== DRY RUN MODE - No topics will be deleted ==="
fi

echo "============================================="
echo "  Kafka Derived Topic Deletion Script"
echo "============================================="
echo ""
echo "Kafka Bootstrap: $KAFKA_BOOTSTRAP"
echo "Preserved Topics: forwardtesting-data, OpenInterest, Orderbook"
echo ""

# Get list of all topics
echo "Fetching topic list..."
ALL_TOPICS=$($KAFKA_BIN/kafka-topics.sh --bootstrap-server $KAFKA_BOOTSTRAP --list 2>/dev/null)

if [ -z "$ALL_TOPICS" ]; then
    echo "ERROR: Could not fetch topics. Check Kafka connectivity."
    exit 1
fi

# Count and collect topics
echo ""
echo "=== Topics to PRESERVE ==="

# Topics to preserve
echo "$ALL_TOPICS" | grep -E "^($PRESERVE_PATTERN)$" | while read topic; do
    echo "  ✅ $topic"
done

echo ""
echo "=== Topics to DELETE ==="

# Topics to delete (everything except preserved)
TOPICS_TO_DELETE=$(echo "$ALL_TOPICS" | grep -vE "^($PRESERVE_PATTERN)$" | grep -v "^__")
DELETE_COUNT=$(echo "$TOPICS_TO_DELETE" | grep -c . || echo 0)

if [ -z "$TOPICS_TO_DELETE" ] || [ "$DELETE_COUNT" -eq 0 ]; then
    echo "No topics to delete. Exiting."
    exit 0
fi

echo "$TOPICS_TO_DELETE" | while read topic; do
    if [ -n "$topic" ]; then
        echo "  ❌ $topic"
    fi
done

echo ""
echo "Total topics to delete: $DELETE_COUNT"

if [ "$DRY_RUN" = true ]; then
    echo ""
    echo "DRY RUN complete. Run without --dry-run to delete topics."
    exit 0
fi

# Confirmation
echo ""
echo "⚠️  WARNING: This will delete $DELETE_COUNT topics!"
printf "Type 'DELETE' to confirm: "
read confirmation

if [ "$confirmation" != "DELETE" ]; then
    echo "Aborted. No topics deleted."
    exit 0
fi

# Delete topics
echo ""
echo "Deleting topics..."

SUCCESS=0
FAILED=0

echo "$TOPICS_TO_DELETE" | while read topic; do
    if [ -n "$topic" ]; then
        printf "  Deleting $topic... "
        if $KAFKA_BIN/kafka-topics.sh --bootstrap-server $KAFKA_BOOTSTRAP --delete --topic "$topic" 2>/dev/null; then
            echo "OK"
        else
            echo "FAILED"
        fi
    fi
done

echo ""
echo "============================================="
echo "  Deletion Complete"
echo "============================================="
