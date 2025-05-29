#!/bin/bash

# Kafka broker configuration
KAFKA_BROKER="172.31.12.118:9092"
KAFKA_TOPICS_CMD="kafka-topics.sh"

echo "🚀 Creating missing Kafka topics for streaming candle application..."

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

echo "🔧 Creating source topics..."

# Create the primary source topic (this is what your streams are actually expecting)
create_topic_if_not_exists "market-data" 3 1

# Alternative source topic (as mentioned in ConsumerApplication.java)
create_topic_if_not_exists "forwardtesting-data" 3 1

echo "🕯️  Creating candlestick output topics..."

# Create all candlestick output topics
create_topic_if_not_exists "1-minute-candle" 3 1
create_topic_if_not_exists "2-min-candle" 3 1
create_topic_if_not_exists "3-min-candle" 3 1
create_topic_if_not_exists "5-min-candle" 3 1
create_topic_if_not_exists "15-min-candle" 3 1
create_topic_if_not_exists "30-min-candle" 3 1

echo "📊 Creating indicator topics..."

# Create indicator topics (for downstream processing)
create_topic_if_not_exists "1-min-candle-indicators" 3 1
create_topic_if_not_exists "2-min-candle-indicators" 3 1
create_topic_if_not_exists "3-min-candle-indicators" 3 1
create_topic_if_not_exists "5-min-candle-indicators" 3 1
create_topic_if_not_exists "15-min-candle-indicators" 3 1
create_topic_if_not_exists "30-min-candle-indicators" 3 1

echo "🔔 Creating signal topics..."

# Create strategy signal topics
create_topic_if_not_exists "bb-supertrend-signals" 3 1
create_topic_if_not_exists "fudkii_Signal" 3 1

echo ""
echo "📋 Listing all created topics:"
$KAFKA_TOPICS_CMD --bootstrap-server $KAFKA_BROKER --list | grep -E "(candle|market-data|forwardtesting|signal)"

echo ""
echo "✅ Topic creation completed!"
echo ""
echo "🔧 Next steps:"
echo "1. Run this script: chmod +x fix-missing-topics.sh && ./fix-missing-topics.sh"
echo "2. Restart your streaming application"
echo "3. Check that tick data is being produced to 'market-data' topic"
echo ""
echo "💡 Note: Make sure your data producer is sending tick data to the 'market-data' topic" 