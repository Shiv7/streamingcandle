#!/bin/bash

# 🔧 MULTI-TIMEFRAME CANDLE FIX DEPLOYMENT
# This script deploys the fix for missing 2m, 3m, 5m, 15m candles

echo "🚀 Deploying Multi-Timeframe Candle Fix..."
echo "Current Time: $(date)"
echo "Target: Fix 2m, 3m, 5m, 15m candle generation"

# Step 1: Build the application
echo "📦 Building application with fixes..."
mvn clean package -DskipTests

if [ $? -ne 0 ]; then
    echo "❌ Build failed! Fix compilation errors first."
    exit 1
fi

# Step 2: Stop existing service
echo "🛑 Stopping existing streaming candle service..."
pkill -f "demo-0.0.1-SNAPSHOT.jar" || echo "No existing service found"

# Step 3: Clean up old state directories (CRITICAL for fixing conflicts)
echo "🧹 Cleaning up old state directories..."
if [ -d "/var/lib/kafka-streams/streamingcandle" ]; then
    sudo rm -rf /var/lib/kafka-streams/streamingcandle/realtime-candle-*
    echo "✅ Cleaned state directories"
fi

# Step 4: Wait for cleanup
echo "⏳ Waiting for cleanup..."
sleep 10

# Step 5: Start the fixed service
echo "🎯 Starting multi-timeframe streaming candle service..."
nohup java -jar -Xmx4g -Xms2g \
    -Dspring.profiles.active=production \
    -Dlogging.level.com.kotsin.consumer.processor=DEBUG \
    target/demo-0.0.1-SNAPSHOT.jar > logs/multi-timeframe-fix.log 2>&1 &

SERVICE_PID=$!
echo "✅ Service started with PID: $SERVICE_PID"

# Step 6: Monitor startup sequence
echo "👀 Monitoring startup sequence..."
sleep 30

# Check if service is healthy
if ps -p $SERVICE_PID > /dev/null; then
    echo "✅ Service is running successfully!"
else
    echo "❌ Service failed to start. Check logs:"
    tail -50 logs/multi-timeframe-fix.log
    exit 1
fi

# Step 7: Monitor candle generation for all timeframes
echo "📊 Monitoring candle generation (checking every 30 seconds for 5 minutes)..."

for i in {1..10}; do
    echo "--- Check $i/10 ($(date)) ---"
    
    # Check each timeframe quickly
    echo "🕯️ Checking all timeframes..."
    
    for timeframe in "1-min" "2-min" "3-min" "5-min" "15-min" "30-min"; do
        # Quick check for messages
        MSG_COUNT=$(timeout 5 kafka-console-consumer.sh \
            --bootstrap-server 172.31.12.118:9092 \
            --topic "${timeframe}-candle" \
            --from-beginning \
            --max-messages 1 \
            --timeout-ms 3000 2>/dev/null | wc -l)
        
        if [ "$MSG_COUNT" -gt 0 ]; then
            echo "  ✅ ${timeframe}: $MSG_COUNT messages"
        else
            echo "  ❌ ${timeframe}: No messages"
        fi
    done
    
    echo ""
    sleep 30
done

# Step 8: Final comprehensive check
echo "🎉 DEPLOYMENT COMPLETE! Running comprehensive check..."
./monitor-all-candles.sh

echo ""
echo "✅ EXPECTED RESULTS:"
echo "   - 1-min, 2-min, 3-min, 5-min, 15-min, 30-min candles ALL producing messages"
echo "   - 1-minute candles should have the most messages"
echo "   - Longer timeframes should have proportionally fewer messages"
echo ""
echo "📈 Monitor continuously with: tail -f logs/multi-timeframe-fix.log"
echo "🔧 Service PID: $SERVICE_PID"
echo ""
echo "🚨 IF STILL MISSING TIMEFRAMES:"
echo "   1. Check logs for 'Configuring multi-minute candles' messages"
echo "   2. Verify 1-minute candles are producing first"
echo "   3. Look for state directory conflicts in logs"
echo "   4. Check consumer group lag for each timeframe" 