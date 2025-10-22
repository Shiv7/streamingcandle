#!/bin/bash

# Create Changelog Topics with Proper Retention
# This prevents OffsetOutOfRangeException by ensuring changelog topics have sufficient retention

KAFKA_BOOTSTRAP_SERVER="${1:-localhost:9092}"

echo "🚀 Creating changelog topics with proper retention configuration..."
echo "📡 Kafka Bootstrap Server: $KAFKA_BOOTSTRAP_SERVER"

# Changelog topics for instrument stream
CHANGELOG_TOPICS=(
    "instrument-instrument-delta-volume-store-changelog"
    "instrument-instrument-state-store-changelog"
    "instrument-KSTREAM-TOTABLE-STATE-STORE-0000000006-changelog"
)

# Configuration
PARTITIONS=6
REPLICATION_FACTOR=1  # Change to 3 for production
RETENTION_MS=604800000  # 7 days
MIN_COMPACTION_LAG_MS=86400000  # 1 day
CLEANUP_POLICY="compact,delete"

echo ""
echo "Configuration:"
echo "  Partitions: $PARTITIONS"
echo "  Replication Factor: $REPLICATION_FACTOR"
echo "  Retention: 7 days ($RETENTION_MS ms)"
echo "  Min Compaction Lag: 1 day ($MIN_COMPACTION_LAG_MS ms)"
echo "  Cleanup Policy: $CLEANUP_POLICY"
echo ""

# Function to create topic
create_topic() {
    local topic=$1
    echo "📝 Creating topic: $topic"
    
    kafka-topics.sh --bootstrap-server "$KAFKA_BOOTSTRAP_SERVER" \
        --create \
        --topic "$topic" \
        --partitions "$PARTITIONS" \
        --replication-factor "$REPLICATION_FACTOR" \
        --config retention.ms="$RETENTION_MS" \
        --config cleanup.policy="$CLEANUP_POLICY" \
        --config min.compaction.lag.ms="$MIN_COMPACTION_LAG_MS" \
        --if-not-exists
    
    if [ $? -eq 0 ]; then
        echo "  ✅ Topic created/exists: $topic"
    else
        echo "  ❌ Failed to create topic: $topic"
    fi
}

# Create all changelog topics
for topic in "${CHANGELOG_TOPICS[@]}"; do
    create_topic "$topic"
    echo ""
done

echo "✅ Changelog topic creation complete!"
echo ""
echo "Verify topics:"
echo "  kafka-topics.sh --bootstrap-server $KAFKA_BOOTSTRAP_SERVER --describe --topic instrument-"

