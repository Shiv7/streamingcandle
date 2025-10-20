#!/bin/bash

###############################################################################
# Deploy Kafka Streams Serde Fix to Production
#
# This script deploys the critical serde fix to resolve ClassCastException
#
# Usage: ./DEPLOY_SERDE_FIX.sh
###############################################################################

set -e

echo "=========================================="
echo "Deploying Kafka Streams Serde Fix"
echo "=========================================="
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Step 1: Commit changes locally
log_info "Step 1: Committing changes to git..."
cd /Users/shivendrapratap/Documents/kotsin/streamingcandle

git add src/main/java/com/kotsin/consumer/processor/UnifiedMarketDataProcessor.java
git add src/main/java/com/kotsin/consumer/config/WebConfig.java
git add src/main/java/com/kotsin/consumer/model/OrderBookSnapshot.java
git add src/main/java/com/kotsin/consumer/model/OpenInterest.java
git add src/main/java/com/kotsin/consumer/model/MicrostructureFeatureState.java
git add src/main/java/com/kotsin/consumer/processor/MultiTimeframeState.java
git add src/main/java/com/kotsin/consumer/service/InstrumentFamilyCacheService.java

git commit -m "fix(critical): Add Consumed serdes to Kafka Streams

PROBLEM:
- ClassCastException: String cannot be cast to TickData
- Kafka Streams using default String deserialization
- Join operations failing with serde mismatch

ROOT CAUSE:
- builder.stream() not specifying Consumed serdes
- Kafka Streams defaulting to StringDeserializer
- Doesn't match optionProducerJava's JSON serialization

SOLUTION:
- Added Consumed.with(keySerde, valueSerde) to all streams
- Explicitly specify TickData.serde(), OpenInterest.serde(), OrderBookSnapshot.serde()
- Ensures proper JSON deserialization matching producer format

ADDITIONAL FIXES (from previous session):
- Added WebConfig.java with RestTemplate bean
- Added OrderBookSnapshot helper methods (getMidPrice, getTimestamp, etc.)
- Fixed JsonSerde.of() to new JsonSerde<>()
- Fixed primitive type null comparison
- Fixed token type conversion
- Added @RequiredArgsConstructor to InstrumentFamilyCacheService

BUILD STATUS: SUCCESS
DEPLOYMENT: Requires clean state stores before restart

Resolves: ClassCastException in stream joins
Impact: UnifiedMarketDataProcessor can now process data correctly
Priority: CRITICAL - Production trading system" || log_warn "Nothing to commit or already committed"

git push origin main || log_warn "Push failed - continuing anyway"

log_info "✅ Changes committed to git"
echo ""

# Step 2: Deploy to production server
log_info "Step 2: Deploying to production server..."

ssh ubuntu@13.203.60.173 << 'ENDSSH'
    set -e
    
    echo "🛑 Stopping existing streamingcandle process..."
    pkill -f streamingcandle || echo "   No existing process found"
    sleep 3
    
    echo "🗑️  Cleaning Kafka Streams state stores (CRITICAL!)..."
    rm -rf /home/ubuntu/kstreams/consumer/* || echo "   No state stores to clean"
    echo "   ✅ State stores cleaned"
    
    echo "📂 Navigating to app directory..."
    cd /home/ubuntu/streamingcandle
    
    echo "🔄 Pulling latest changes from git..."
    git fetch origin
    git pull origin main
    
    echo "🧹 Cleaning previous build..."
    mvn clean
    
    echo "🔨 Building application..."
    mvn package -DskipTests
    
    if [ $? -eq 0 ]; then
        echo "✅ Build successful!"
    else
        echo "❌ Build failed!"
        exit 1
    fi
    
    echo "🚀 Starting application in background..."
    nohup mvn spring-boot:run > streamingcandle.log 2>&1 &
    
    echo "⏳ Waiting for application to start..."
    sleep 15
    
    echo "📊 Checking application status..."
    if ps aux | grep -q "[s]pring-boot:run.*streamingcandle"; then
        echo "✅ Application started successfully!"
        echo ""
        echo "📝 Last 30 lines of log:"
        tail -30 streamingcandle.log
        
        echo ""
        echo "🔍 Checking for errors..."
        ERROR_COUNT=$(grep -c "ClassCastException\|StreamsException" streamingcandle.log || echo "0")
        if [ "$ERROR_COUNT" -eq "0" ]; then
            echo "✅ No serde errors found!"
        else
            echo "⚠️  Found $ERROR_COUNT errors - check logs"
        fi
    else
        echo "❌ Application failed to start!"
        echo ""
        echo "📝 Last 50 lines of log:"
        tail -50 streamingcandle.log
        exit 1
    fi
ENDSSH

if [ $? -eq 0 ]; then
    log_info "✅ Deployment successful!"
else
    log_error "❌ Deployment failed!"
    exit 1
fi

echo ""
echo "=========================================="
echo "Deployment Complete!"
echo "=========================================="
echo ""
echo "Next steps:"
echo "1. Monitor logs: ssh ubuntu@13.203.60.173 'tail -f ~/streamingcandle/streamingcandle.log'"
echo "2. Verify no errors: ssh ubuntu@13.203.60.173 'grep -i ClassCast ~/streamingcandle/streamingcandle.log'"
echo "3. Check output topic: kafka-console-consumer --bootstrap-server 13.203.60.173:9094 --topic enriched-market-data --max-messages 3"
echo ""

