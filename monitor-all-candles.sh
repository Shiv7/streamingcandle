#!/bin/bash

# 🔍 MONITOR ALL CANDLE TIMEFRAMES
# This script checks all candle topics to see which ones are producing messages

echo "🕯️ MONITORING ALL CANDLE TIMEFRAMES"
echo "Current Time: $(date)"
echo "Kafka Broker: 172.31.12.118:9092"
echo "=================================="

# Function to check topic message count
check_topic() {
    local topic=$1
    echo "📊 Checking $topic..."
    
    # Get latest offset for all partitions
    timeout 10 kafka-console-consumer.sh \
        --bootstrap-server 172.31.12.118:9092 \
        --topic $topic \
        --from-beginning \
        --max-messages 1 \
        --timeout-ms 5000 2>/dev/null | wc -l
}

# Check all candle topics
echo "1️⃣ 1-minute candles:"
MSG_COUNT_1M=$(check_topic "1-min-candle")
echo "   Messages: $MSG_COUNT_1M"

echo ""
echo "2️⃣ 2-minute candles:"
MSG_COUNT_2M=$(check_topic "2-min-candle")
echo "   Messages: $MSG_COUNT_2M"

echo ""
echo "3️⃣ 3-minute candles:"
MSG_COUNT_3M=$(check_topic "3-min-candle")
echo "   Messages: $MSG_COUNT_3M"

echo ""
echo "5️⃣ 5-minute candles:"
MSG_COUNT_5M=$(check_topic "5-min-candle")
echo "   Messages: $MSG_COUNT_5M"

echo ""
echo "🔥 15-minute candles:"
MSG_COUNT_15M=$(check_topic "15-min-candle")
echo "   Messages: $MSG_COUNT_15M"

echo ""
echo "🎯 30-minute candles:"
MSG_COUNT_30M=$(check_topic "30-min-candle")
echo "   Messages: $MSG_COUNT_30M"

echo ""
echo "=================================="
echo "📋 SUMMARY:"
echo "   1-min:  $MSG_COUNT_1M messages"
echo "   2-min:  $MSG_COUNT_2M messages"  
echo "   3-min:  $MSG_COUNT_3M messages"
echo "   5-min:  $MSG_COUNT_5M messages"
echo "   15-min: $MSG_COUNT_15M messages"
echo "   30-min: $MSG_COUNT_30M messages"

# Check consumer groups
echo ""
echo "🔧 CONSUMER GROUPS:"
kafka-consumer-groups.sh --bootstrap-server 172.31.12.118:9092 --list | grep -E "realtime-candle|candle-processor"

echo ""
echo "🎯 EXPECTED BEHAVIOR:"
echo "✅ All timeframes should have > 0 messages"
echo "✅ 1-minute should have the most messages"
echo "✅ Longer timeframes should have fewer messages"
echo ""
echo "🚨 IF ISSUES:"
echo "   - Check streaming candle service logs"
echo "   - Verify 1-minute candles are producing first"
echo "   - Check for state directory conflicts" 