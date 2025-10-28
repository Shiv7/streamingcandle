#!/bin/bash

# Clean State Stores Script
# Use this after refactoring to remove incompatible state from old architecture

echo "🧹 Cleaning up old Kafka Streams state stores..."
echo ""

# Stop the application first
echo "⚠️  IMPORTANT: Stop the application BEFORE running this script!"
echo "   Press Ctrl+C to cancel, or Enter to continue..."
read

# State directories (check both)
STATE_DIRS=(
    "/tmp/kafka-streams/streamingcandle"
    "/var/lib/kafka-streams/streamingcandle"
    "/home/ubuntu/kstreams/streamingcandle"
)

# Find which directory exists
TARGET_DIR=""
for dir in "${STATE_DIRS[@]}"; do
    if [ -d "$dir" ]; then
        echo "📂 Found state directory: $dir"
        TARGET_DIR="$dir"
        break
    fi
done

if [ -z "$TARGET_DIR" ]; then
    echo "❌ No state directory found. Looking for custom locations..."
    echo "Checking /tmp/kafka-streams..."
    if [ -d "/tmp/kafka-streams" ]; then
        echo "📂 Found: /tmp/kafka-streams"
        ls -la /tmp/kafka-streams/
    fi
    exit 0
fi

# Backup before deletion
BACKUP_DIR="${TARGET_DIR}_backup_$(date +%Y%m%d_%H%M%S)"
echo ""
echo "💾 Creating backup at: $BACKUP_DIR"
cp -r "$TARGET_DIR" "$BACKUP_DIR"

# Delete state stores
echo "🗑️  Deleting state stores..."
rm -rf "$TARGET_DIR"

echo ""
echo "✅ State stores cleaned successfully!"
echo "📋 Backup location: $BACKUP_DIR"
echo ""
echo "Next steps:"
echo "1. Start the application: mvn spring-boot:run"
echo "2. State stores will be rebuilt from scratch (clean slate)"
echo "3. Monitor logs for successful startup"
echo ""
echo "If you need to restore from backup:"
echo "  cp -r $BACKUP_DIR $TARGET_DIR"
echo ""

