#!/bin/bash

echo "🚀 COMPREHENSIVE FIX FOR STREAMING CANDLE TIME FILTERING"
echo "========================================================="

# Kafka broker configuration
KAFKA_BROKER="172.31.12.118:9092"
KAFKA_TOPICS_CMD="kafka-topics.sh"

echo ""
echo "🔧 Step 1: Creating missing Kafka topics..."

# Function to create topic if it doesn't exist
create_topic_if_not_exists() {
    local topic_name=$1
    local partitions=${2:-3}
    local replication_factor=${3:-1}
    
    echo "📝 Checking topic: $topic_name"
    
    # Check if topic exists
    if $KAFKA_TOPICS_CMD --bootstrap-server $KAFKA_BROKER --list | grep -q "^${topic_name}$"; then
        echo "✅ Topic '$topic_name' already exists"
    else
        echo "⚠️  Topic '$topic_name' does not exist. Creating..."
        $KAFKA_TOPICS_CMD --bootstrap-server $KAFKA_BROKER \
            --create \
            --topic $topic_name \
            --partitions $partitions \
            --replication-factor $replication_factor
            
        if [ $? -eq 0 ]; then
            echo "✅ Successfully created topic '$topic_name'"
        else
            echo "❌ Failed to create topic '$topic_name'"
        fi
    fi
    echo ""
}

# Create the primary source topic (this is what your streams are actually expecting)
create_topic_if_not_exists "market-data" 3 1
create_topic_if_not_exists "forwardtesting-data" 3 1

# Create all candlestick output topics
create_topic_if_not_exists "1-minute-candle" 3 1
create_topic_if_not_exists "2-min-candle" 3 1
create_topic_if_not_exists "3-min-candle" 3 1
create_topic_if_not_exists "5-min-candle" 3 1
create_topic_if_not_exists "15-min-candle" 3 1
create_topic_if_not_exists "30-min-candle" 3 1

# Create indicator topics (for downstream processing)
create_topic_if_not_exists "1-min-candle-indicators" 3 1
create_topic_if_not_exists "2-min-candle-indicators" 3 1
create_topic_if_not_exists "3-min-candle-indicators" 3 1
create_topic_if_not_exists "5-min-candle-indicators" 3 1
create_topic_if_not_exists "15-min-candle-indicators" 3 1
create_topic_if_not_exists "30-min-candle-indicators" 3 1

# Create strategy signal topics
create_topic_if_not_exists "bb-supertrend-signals" 3 1
create_topic_if_not_exists "fudkii_Signal" 3 1

echo ""
echo "📋 Listing all created topics:"
$KAFKA_TOPICS_CMD --bootstrap-server $KAFKA_BROKER --list | grep -E "(candle|market-data|forwardtesting|signal)"

echo ""
echo "✅ Topic creation completed!"
echo ""
echo "🔧 Step 2: CODE FIXES APPLIED"
echo "================================"
echo ""
echo "✅ Option Producer (optionProducerJava):"
echo "   - Enhanced NSE time filtering: STRICT 9:15 AM - 3:30 PM"
echo "   - Added MCX time filtering: 9:00 AM - 11:30 PM"
echo "   - Fixed commodity scheduler: Now starts at 3:30 PM (when NSE closes)"
echo "   - Added detailed logging for blocked messages"
echo ""
echo "✅ Indicator Calculator (indiactorCalculator):"
echo "   - Added exchange-specific time filtering"
echo "   - NSE: 9:15 AM - 3:30 PM ONLY"
echo "   - MCX: 9:00 AM - 11:30 PM"
echo "   - Enhanced logging for blocked candles"
echo ""
echo "✅ Indicator Aggregator (indicatorAgg):"
echo "   - Added time filtering (was missing before)"
echo "   - NSE: 9:15 AM - 3:30 PM ONLY"
echo "   - MCX: 9:00 AM - 11:30 PM"
echo "   - Blocks NSE indicators after market close"
echo ""
echo "✅ Streaming Candle (streamingcandle):"
echo "   - Enhanced tick filtering with detailed logging"
echo "   - Fixed consumer deserializer configuration"
echo "   - Updated input topic to match data source"
echo ""
echo "🚨 CRITICAL TIME FILTERING RULES NOW ENFORCED:"
echo "=============================================="
echo ""
echo "📊 NSE (Exchange: 'N') - STRICT FILTERING:"
echo "   ⏰ Trading Hours: 9:15 AM - 3:30 PM IST"
echo "   🚫 NO NSE data processed after 3:30 PM"
echo "   📈 Includes: Equity, Derivatives, Options"
echo ""
echo "🌾 MCX (Exchange: 'M') - COMMODITY FILTERING:"
echo "   ⏰ Trading Hours: 9:00 AM - 11:30 PM IST"
echo "   ✅ Commodity data continues after NSE close"
echo "   📊 Includes: Commodity, Currency"
echo ""
echo "🔧 NEXT STEPS:"
echo "=============="
echo "1. Restart all applications to apply the fixes:"
echo "   - Option Producer"
echo "   - Indicator Calculator"
echo "   - Indicator Aggregator"
echo "   - Streaming Candle"
echo ""
echo "2. Monitor logs for 'BLOCKED' messages to verify filtering"
echo ""
echo "3. Check current time and expected behavior:"
CURRENT_TIME=$(date '+%H:%M')
echo "   Current IST time: $CURRENT_TIME"
if [[ "$CURRENT_TIME" > "15:30" ]] && [[ "$CURRENT_TIME" < "23:30" ]]; then
    echo "   ✅ Should see: MCX/Commodity data flowing"
    echo "   🚫 Should NOT see: Any NSE data"
elif [[ "$CURRENT_TIME" > "09:15" ]] && [[ "$CURRENT_TIME" < "15:30" ]]; then
    echo "   ✅ Should see: NSE data flowing"
    echo "   ⏳ MCX starts at 9:00 AM, overlaps with NSE"
else
    echo "   🚫 Outside all trading hours - no data should flow"
fi
echo ""
echo "4. Verify data flow:"
echo "   - Check Kafka topics for messages"
echo "   - Monitor application logs"
echo "   - Confirm no NSE data after 3:30 PM"
echo ""
echo "💡 SUCCESS INDICATORS:"
echo "====================="
echo "✅ NSE data stops flowing after 3:30 PM"
echo "✅ MCX/Commodity data continues until 11:30 PM"
echo "✅ Commodity scheduler starts at 3:30 PM (not 8:59 PM)"
echo "✅ No more 'Missing source topics' errors"
echo "✅ JSON deserialization works properly"
echo ""
echo "🎉 COMPREHENSIVE FIX COMPLETED!" 