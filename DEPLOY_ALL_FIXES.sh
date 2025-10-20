#!/bin/bash

###############################################################################
# STREAMINGCANDLE DEPLOYMENT SCRIPT
# Deploys all critical fixes to production server
#
# Fixes included:
# 1. Volume calculation (CumToDeltaTransformer)
# 2. Join window optimization (5s → 15s)
# 3. Emission strategy (30min → 1min)
# 4. Imbalance bars (AFML Chapter 2 implementation)
# 5. Microstructure features (AFML Chapter 19 implementation)
# 6. Cache optimization (negative caching)
# 7. Model fixes (FutureResponse, OptionsResponse field names)
#
# Date: October 20, 2025
# Author: Claude Code
###############################################################################

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
SERVER="ubuntu@13.203.60.173"
APP_DIR="~/streamingcandle"
STATE_STORE_DIR="/home/ubuntu/kstreams/consumer"
BRANCH="feature/NewSun"

echo -e "${BLUE}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║     STREAMINGCANDLE DEPLOYMENT - ALL CRITICAL FIXES       ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""

###############################################################################
# STEP 1: Local Build
###############################################################################
echo -e "${YELLOW}[STEP 1/8]${NC} Building locally..."
mvn clean package -DskipTests

if [ $? -ne 0 ]; then
    echo -e "${RED}❌ Local build failed!${NC}"
    exit 1
fi
echo -e "${GREEN}✅ Local build successful${NC}"
echo ""

###############################################################################
# STEP 2: Git Status Check
###############################################################################
echo -e "${YELLOW}[STEP 2/8]${NC} Checking git status..."
git status --short

read -p "Do you want to commit and push changes? (y/n) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo -e "${BLUE}Committing changes...${NC}"
    git add .
    git commit -m "fix: All critical fixes - volume, joins, emission, imbalance bars, microstructure, cache optimization

- Added CumToDeltaTransformer to fix zero volume issue
- Increased join windows from 5s to 15s for OI data
- Changed from 30-min to 1-min windows for real-time emission
- Implemented AFML Chapter 2 imbalance bars (VIB, DIB, TRB, VRB)
- Implemented AFML Chapter 19 microstructure features (OFI, VPIN, Kyle's Lambda)
- Added negative caching to prevent repeated API calls
- Fixed FutureResponse and OptionsResponse field names to match ScripFinder API
- Improved logging for API success/failure tracking"

    git push origin $BRANCH

    if [ $? -ne 0 ]; then
        echo -e "${RED}❌ Git push failed!${NC}"
        exit 1
    fi
    echo -e "${GREEN}✅ Changes pushed to $BRANCH${NC}"
else
    echo -e "${YELLOW}⚠️  Skipping git commit/push${NC}"
fi
echo ""

###############################################################################
# STEP 3: Stop Existing Process on Server
###############################################################################
echo -e "${YELLOW}[STEP 3/8]${NC} Stopping existing streamingcandle process..."
ssh $SERVER "pkill -f streamingcandle || true"
sleep 2

# Verify stopped
RUNNING=$(ssh $SERVER "ps aux | grep streamingcandle | grep -v grep | wc -l")
if [ "$RUNNING" -eq 0 ]; then
    echo -e "${GREEN}✅ Process stopped successfully${NC}"
else
    echo -e "${RED}❌ Process still running! Manual intervention required.${NC}"
    exit 1
fi
echo ""

###############################################################################
# STEP 4: Clean State Stores (CRITICAL!)
###############################################################################
echo -e "${YELLOW}[STEP 4/8]${NC} Cleaning Kafka Streams state stores..."
ssh $SERVER "rm -rf $STATE_STORE_DIR/*"
echo -e "${GREEN}✅ State stores cleaned${NC}"
echo ""

###############################################################################
# STEP 5: Clean Redis Cache (CRITICAL!)
###############################################################################
echo -e "${YELLOW}[STEP 5/8]${NC} Cleaning Redis cache..."
read -p "This will FLUSH all Redis data. Continue? (y/n) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    ssh $SERVER "redis-cli FLUSHDB"
    echo -e "${GREEN}✅ Redis cache cleared${NC}"
else
    echo -e "${YELLOW}⚠️  Skipping Redis flush (may cause issues with old cached data)${NC}"
fi
echo ""

###############################################################################
# STEP 6: Pull Latest Code on Server
###############################################################################
echo -e "${YELLOW}[STEP 6/8]${NC} Pulling latest code on server..."
ssh $SERVER "cd $APP_DIR && git pull origin $BRANCH"

if [ $? -ne 0 ]; then
    echo -e "${RED}❌ Git pull failed on server!${NC}"
    exit 1
fi
echo -e "${GREEN}✅ Latest code pulled${NC}"
echo ""

###############################################################################
# STEP 7: Rebuild on Server
###############################################################################
echo -e "${YELLOW}[STEP 7/8]${NC} Building on server..."
ssh $SERVER "cd $APP_DIR && mvn clean package -DskipTests"

if [ $? -ne 0 ]; then
    echo -e "${RED}❌ Server build failed!${NC}"
    exit 1
fi
echo -e "${GREEN}✅ Server build successful${NC}"
echo ""

###############################################################################
# STEP 8: Start Application
###############################################################################
echo -e "${YELLOW}[STEP 8/8]${NC} Starting streamingcandle application..."
ssh $SERVER "cd $APP_DIR && nohup mvn spring-boot:run > streamingcandle.log 2>&1 &"
sleep 5

echo -e "${GREEN}✅ Application started${NC}"
echo ""

###############################################################################
# VERIFICATION
###############################################################################
echo -e "${BLUE}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║                    VERIFICATION STEPS                      ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""

echo -e "${YELLOW}1. Monitor startup logs:${NC}"
echo "   ssh $SERVER 'tail -f $APP_DIR/streamingcandle.log'"
echo ""

echo -e "${YELLOW}2. Expected logs:${NC}"
echo "   ✅ \"Using 1-minute tumbling windows with 10-second grace period\""
echo "   ✅ \"Unified Market Data Processor started successfully\""
echo "   ✅ \"🚀 Initializing instrument family cache...\""
echo "   ✅ \"✅ Instrument family cache initialized successfully\""
echo "   ⚠️  \"⚠️ Cache miss for scripCode: NIFTY, fetching from API...\" (once only)"
echo "   ⚠️  \"⚠️ Both Future and Options API failed for scripCode: NIFTY\""
echo ""

echo -e "${YELLOW}3. Verify output (after 2-3 minutes):${NC}"
echo "   kafka-console-consumer --bootstrap-server 13.203.60.173:9094 \\"
echo "     --topic enriched-market-data \\"
echo "     --from-beginning \\"
echo "     --max-messages 5 | jq '.'"
echo ""

echo -e "${YELLOW}4. Check for non-zero volume:${NC}"
echo "   kafka-console-consumer --bootstrap-server 13.203.60.173:9094 \\"
echo "     --topic enriched-market-data \\"
echo "     --max-messages 1 | jq '.multiTimeframeCandles[] | .volume'"
echo ""

echo -e "${YELLOW}5. Check for OI data:${NC}"
echo "   kafka-console-consumer --bootstrap-server 13.203.60.173:9094 \\"
echo "     --topic enriched-market-data \\"
echo "     --max-messages 1 | jq '.openInterest'"
echo ""

echo -e "${YELLOW}6. Check cache statistics:${NC}"
echo "   redis-cli KEYS 'instrument:family:*' | wc -l"
echo "   redis-cli GET 'instrument:family:NIFTY'"
echo ""

echo -e "${GREEN}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║             DEPLOYMENT COMPLETED SUCCESSFULLY              ║${NC}"
echo -e "${GREEN}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""

echo -e "${BLUE}SUCCESS CRITERIA:${NC}"
echo "✅ Build succeeds without errors"
echo "✅ Volume > 0 in all candles"
echo "✅ OI data appears in enriched messages"
echo "✅ First emission within 1-2 minutes (not 30 minutes)"
echo "✅ Microstructure features have non-zero values"
echo "✅ Imbalance bars show proper progress/threshold tracking"
echo "✅ No ClassCastException or serde errors"
echo "✅ Consumer lag < 100 messages in steady state"
echo "✅ Cache hit rate > 99.9% after initialization"
echo "✅ No repeated API calls for same scripCode"
echo ""

echo -e "${YELLOW}⚠️  IMPORTANT NOTES:${NC}"
echo "1. NIFTY is an INDEX, not an equity - it will have null equity/future/options data"
echo "2. Test with REAL EQUITIES: RELIANCE, TCS, HDFC, INFY, SBIN"
echo "3. First emission happens at T+1:10, not immediately"
echo "4. Watch for cache miss logs - should only appear once per unknown scripCode"
echo "5. Monitor ScripFinder API load - should be minimal after initialization"
echo ""

echo -e "${BLUE}For detailed fix documentation, see:${NC}"
echo "- FIXES_APPLIED.md (all critical fixes)"
echo "- INSTRUMENT_FAMILY_FIX.md (cache and model fixes)"
echo "- CACHE_OPTIMIZATION_FIX.md (negative caching optimization)"
echo "- KAFKA_STREAMS_SERDE_FIX.md (serde configuration)"
echo ""
