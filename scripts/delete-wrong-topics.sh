#!/bin/bash

# Delete Incorrectly Created Changelog Topics
# These were created with 6 partitions but Kafka Streams expects 1 partition

KAFKA_BOOTSTRAP_SERVER="${1:-localhost:9092}"
KAFKA_BIN="${2:-/opt/kafka/bin}"

echo "🗑️ Deleting incorrectly created changelog topics..."
echo "📡 Kafka Bootstrap Server: $KAFKA_BOOTSTRAP_SERVER"
echo ""

# List of changelog topics to delete
TOPICS_TO_DELETE="
instrument-instrument-delta-volume-store-changelog
instrument-instrument-state-store-changelog
instrument-KSTREAM-TOTABLE-STATE-STORE-0000000006-changelog
"

echo "⚠️ WARNING: This will delete the following topics:"
for topic in $TOPICS_TO_DELETE; do
    echo "  - $topic"
done
echo ""
echo "These will be auto-recreated by Kafka Streams with correct partition count."
echo ""
read -p "Press Enter to continue or Ctrl+C to cancel..."

for topic in $TOPICS_TO_DELETE; do
    echo "🗑️ Deleting: $topic"
    $KAFKA_BIN/kafka-topics.sh --bootstrap-server "$KAFKA_BOOTSTRAP_SERVER" \
        --delete --topic "$topic" 2>&1
    
    if [ $? -eq 0 ]; then
        echo "  ✅ Deleted: $topic"
    else
        echo "  ⚠️ Topic may not exist or already deleted: $topic"
    fi
done

echo ""
echo "✅ Cleanup complete!"
echo ""
echo "Next steps:"
echo "1. Start your application"
echo "2. Kafka Streams will auto-create changelog topics with correct partitions"
echo "3. Monitor logs for successful startup"

