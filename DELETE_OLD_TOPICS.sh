#!/bin/bash

# DELETE OLD CHANGELOG AND REPARTITION TOPICS
# These contain serialized data from the OLD complex architecture
# MUST be deleted for clean refactor to work!

echo "🗑️  DELETING OLD KAFKA TOPICS WITH INCOMPATIBLE DATA"
echo "================================================"
echo ""

KAFKA_BOOTSTRAP="localhost:9092"

echo "⚠️  This will delete ALL changelog and repartition topics!"
echo "   Old state stores MUST be cleaned first."
echo ""
echo "Press Enter to continue or Ctrl+C to cancel..."
read

echo ""
echo "📋 Finding all changelog and repartition topics..."

# Get all changelog and repartition topics
TOPICS=$(kafka-topics --bootstrap-server $KAFKA_BOOTSTRAP --list 2>/dev/null | grep -E '(changelog|repartition)')

if [ -z "$TOPICS" ]; then
    echo "✅ No changelog/repartition topics found (clean slate!)"
    exit 0
fi

echo "Found topics to delete:"
echo "$TOPICS"
echo ""

# Delete each topic
echo "$TOPICS" | while read topic; do
    echo "🗑️  Deleting: $topic"
    kafka-topics --bootstrap-server $KAFKA_BOOTSTRAP --delete --topic "$topic" 2>&1 | grep -v "marked for deletion"
done

echo ""
echo "✅ All old topics deleted!"
echo ""
echo "Next steps:"
echo "1. Rebuild: mvn clean package -DskipTests"
echo "2. Start: mvn spring-boot:run"
echo "3. Verify: All streams should start clean with new models"
echo ""

