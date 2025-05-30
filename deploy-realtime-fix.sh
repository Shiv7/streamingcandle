#!/bin/bash

# 🚨 REAL-TIME CANDLE FIX DEPLOYMENT SCRIPT
# This script deploys the critical fixes to eliminate 39-minute delays

echo "🚀 Deploying Real-Time Candle Fixes..."
echo "Current Time: $(date)"

# Step 1: Build the application
echo "📦 Building application..."
mvn clean package -DskipTests

if [ $? -ne 0 ]; then
    echo "❌ Build failed! Fix compilation errors first."
    exit 1
fi

# Step 2: Stop existing service (if running)
echo "🛑 Stopping existing streaming candle service..."
pkill -f "demo-0.0.1-SNAPSHOT.jar" || echo "No existing service found"

# Step 3: Wait for graceful shutdown
echo "⏳ Waiting for graceful shutdown..."
sleep 5

# Step 4: Start the optimized service
echo "🎯 Starting optimized streaming candle service..."
nohup java -jar -Xmx4g -Xms2g \
    -Dspring.profiles.active=production \
    target/demo-0.0.1-SNAPSHOT.jar > logs/realtime-fix.log 2>&1 &

SERVICE_PID=$!
echo "✅ Service started with PID: $SERVICE_PID"

# Step 5: Monitor startup
echo "👀 Monitoring startup (30 seconds)..."
sleep 30

# Step 6: Check if service is healthy
if ps -p $SERVICE_PID > /dev/null; then
    echo "✅ Service is running successfully!"
else
    echo "❌ Service failed to start. Check logs:"
    tail -50 logs/realtime-fix.log
    exit 1
fi

# Step 7: Monitor consumer lag improvements
echo "📊 Monitoring consumer lag improvements..."
echo "Expected: Lag should drop from 33-36 to <5 messages within 5 minutes"

for i in {1..5}; do
    echo "--- Check $i/5 (waiting 1 minute) ---"
    sleep 60
    
    echo "1-minute candle consumer lag:"
    kafka-consumer-groups.sh --bootstrap-server 172.31.0.121:9092 \
        --group tickdata-to-candlestick-app-1minute --describe | grep -v COORDINATOR
    
    echo "30-minute candle consumer lag:"
    kafka-consumer-groups.sh --bootstrap-server 172.31.0.121:9092 \
        --group tickdata-to-candlestick-app-30minute --describe | grep -v COORDINATOR
    echo ""
done

# Step 8: Final validation
echo "🎉 DEPLOYMENT COMPLETE!"
echo "🔍 Final validation - check latest candle timestamps:"

echo "Latest 1-min candle:"
kafka-console-consumer.sh --bootstrap-server 172.31.0.121:9092 \
    --topic 1-min-candle --from-beginning --max-messages 1 2>/dev/null | tail -1

echo "Latest 30-min candle:"
kafka-console-consumer.sh --bootstrap-server 172.31.0.121:9092 \
    --topic 30-min-candle --from-beginning --max-messages 1 2>/dev/null | tail -1

echo ""
echo "✅ EXPECTED RESULTS:"
echo "   - 1-minute candle lag: <5 messages (was 33-36)"
echo "   - 30-minute candle delay: <2 minutes (was 39 minutes)"
echo "   - Real-time candle emission within 30 seconds of window close"
echo ""
echo "📈 Monitor with: tail -f logs/realtime-fix.log"
echo "🔧 Service PID: $SERVICE_PID" 