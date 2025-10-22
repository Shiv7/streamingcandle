#!/bin/bash

# Cleanup Corrupted State Stores Script
# Use this when you see OffsetOutOfRangeException or TaskCorruptedException

echo "🧹 Cleaning up corrupted Kafka Streams state stores..."

# Stop the application first
echo "⚠️  Make sure the application is STOPPED before running this!"
read -p "Press Enter to continue or Ctrl+C to cancel..."

# Default state directory
STATE_DIR="/tmp/kafka-streams/streamingcandle"
PROD_STATE_DIR="/var/lib/kafka-streams/streamingcandle"

# Check which state directory exists
if [ -d "$PROD_STATE_DIR" ]; then
    echo "📂 Found production state directory: $PROD_STATE_DIR"
    TARGET_DIR="$PROD_STATE_DIR"
elif [ -d "$STATE_DIR" ]; then
    echo "📂 Found default state directory: $STATE_DIR"
    TARGET_DIR="$STATE_DIR"
else
    echo "❌ No state directory found. Nothing to clean."
    exit 0
fi

# Backup state stores before deletion
BACKUP_DIR="${TARGET_DIR}_backup_$(date +%Y%m%d_%H%M%S)"
echo "💾 Creating backup at: $BACKUP_DIR"
cp -r "$TARGET_DIR" "$BACKUP_DIR"

# Delete corrupted state stores
echo "🗑️  Deleting state stores..."
rm -rf "$TARGET_DIR"

echo "✅ State stores cleaned up successfully!"
echo "📋 Backup location: $BACKUP_DIR"
echo ""
echo "Next steps:"
echo "1. Start the application"
echo "2. State stores will be rebuilt from changelog topics"
echo "3. Monitor logs for 'State restoration complete'"
echo ""
echo "If you need to restore from backup:"
echo "  cp -r $BACKUP_DIR $TARGET_DIR"

