# Comprehensive Verification Guide - All 7 Phases

## Overview

This guide helps you verify **ALL 7 phases** of the implementation in **one go** using the comprehensive debug logs.

## What Gets Logged

Every `InstrumentCandle` that's built now logs a **complete verification JSON** containing:

### Phase 1: Trade Classification ✅
- `aggressiveBuyVolume`, `aggressiveSellVolume`, `midpointVolume`
- `classificationReliability`, `buyPressure`, `sellPressure`
- `buyVolume`, `sellVolume`, `volume`

### Phase 2: Temporal Tracking ✅
- `firstTickTimestamp`, `lastTickTimestamp`
- `minTickGap`, `maxTickGap`, `avgTickGap`
- `ticksPerSecond`, `tickAcceleration`, `tickCount`

### Phase 3: Orderbook Depth Fragmentation ✅
- `totalBidOrders`, `totalAskOrders`
- `ordersAtBestBid`, `ordersAtBestAsk`
- `avgBidOrderSize`, `avgAskOrderSize`
- `depthConcentration`, `maxDepthLevels`
- `icebergAtBestBid`, `icebergAtBestAsk`

### Phase 4: Trade Size Distribution ✅
- `maxTradeSize`, `minTradeSize`, `avgTradeSize`, `medianTradeSize`
- `largeTradeCount`, `priceImpactPerUnit`

### Phase 5: OI Correlation ✅
- `priceAtOIUpdate`, `volumeAtOIUpdate`, `spreadAtOIUpdate`
- `oiUpdateLatency`, `oiUpdateCount`
- `oiChange`, `oiChangePercent`

### Phase 6: Cross-Stream Latency ✅
- `tickStale`, `orderbookStale`, `oiStale`
- `maxDataAge`, `tickToOrderbookLatency`, `tickToOILatency`
- `stalenessReason`

### Phase 7: Orderbook Update Dynamics ✅
- `spreadVolatility`, `maxSpread`, `minSpread`
- `orderbookUpdateCount`, `spreadChangeRate`, `orderbookMomentum`

### Metadata ✅
- `scripCode`, `windowStartMillis`, `windowEndMillis`
- `hasOrderbook`, `hasOI`, `vpin`, `vpinBucketCount`
- `isReplay`, `quality`

## How to Verify

### Step 1: Run the Application

```bash
# Rebuild
mvn clean compile

# Run (or restart if already running)
# The application will start logging to .cursor/debug.log
```

### Step 2: Wait for Data Processing

Wait for at least one `InstrumentCandle` to be emitted (check your output topic or wait 1-2 minutes).

### Step 3: Check the Log File

```bash
# View all verification logs
cat logs/debug.log | grep "ALL-PHASES-VERIFY" | jq .

# Or for a specific scripCode
cat logs/debug.log | grep "ALL-PHASES-VERIFY" | jq 'select(.data.metadata.scripCode == "97104")'
```

### Step 4: Verify Each Phase

#### ✅ Phase 1: Trade Classification
```bash
cat logs/debug.log | grep "ALL-PHASES-VERIFY" | jq '.data.phase1_tradeClassification'
```

**Expected:**
- `aggressiveBuyVolume` + `aggressiveSellVolume` + `midpointVolume` ≈ `volume`
- `classificationReliability` > 0.0 (ideally > 0.7)
- `buyPressure` + `sellPressure` ≈ 1.0 (if midpointVolume is small)

**Bug Check:**
- ❌ If `midpointVolume` = 2x `volume` → **DOUBLE COUNTING BUG** (should be fixed)
- ✅ If `midpointVolume` ≤ `volume` → **CORRECT**

#### ✅ Phase 2: Temporal Tracking
```bash
cat .cursor/debug.log | grep "ALL-PHASES-VERIFY" | jq '.data.phase2_temporalTracking'
```

**Expected:**
- `firstTickTimestamp` < `lastTickTimestamp`
- `ticksPerSecond` > 0 (if tickCount > 0)
- `avgTickGap` > 0 (if tickCount > 1)

#### ✅ Phase 3: Orderbook Depth Fragmentation
```bash
cat .cursor/debug.log | grep "ALL-PHASES-VERIFY" | jq '.data.phase3_orderbookDepth'
```

**Expected (if orderbook present):**
- `totalBidOrders` > 0
- `totalAskOrders` > 0
- `depthConcentration` between 0.0 and 1.0
- `icebergAtBestBid` or `icebergAtBestAsk` may be true/false

#### ✅ Phase 4: Trade Size Distribution
```bash
cat .cursor/debug.log | grep "ALL-PHASES-VERIFY" | jq '.data.phase4_tradeSize'
```

**Expected:**
- `maxTradeSize` ≥ `minTradeSize`
- `avgTradeSize` > 0 (if volume > 0)
- `largeTradeCount` ≥ 0

#### ✅ Phase 5: OI Correlation
```bash
cat .cursor/debug.log | grep "ALL-PHASES-VERIFY" | jq '.data.phase5_oiCorrelation'
```

**Expected (if OI present):**
- `priceAtOIUpdate` != null
- `volumeAtOIUpdate` != null
- `oiUpdateLatency` ≥ 0

#### ✅ Phase 6: Cross-Stream Latency
```bash
cat .cursor/debug.log | grep "ALL-PHASES-VERIFY" | jq '.data.phase6_crossStreamLatency'
```

**Expected:**
- `tickStale` should be `false` for fresh data (unless truly stale)
- `maxDataAge` < 5000 for fresh data (unless replay mode)
- `stalenessReason` should be empty for fresh data

**Bug Check:**
- ❌ If `tickStale` = true but data is fresh → **STALENESS BUG** (should be fixed with replay detection)
- ✅ If `tickStale` = false for fresh data → **CORRECT**

#### ✅ Phase 7: Orderbook Update Dynamics
```bash
cat .cursor/debug.log | grep "ALL-PHASES-VERIFY" | jq '.data.phase7_orderbookDynamics'
```

**Expected (if orderbook present):**
- `orderbookUpdateCount` > 0
- `spreadVolatility` may be null or >= 0
- `maxSpread` ≥ `minSpread` (if both present)

## Quick Verification Script

Save this as `verify_all_phases.sh`:

```bash
#!/bin/bash

LOG_FILE="logs/debug.log"

echo "=== COMPREHENSIVE VERIFICATION - ALL 7 PHASES ==="
echo ""

# Get latest verification log
LATEST=$(grep "ALL-PHASES-VERIFY" "$LOG_FILE" | tail -1)

if [ -z "$LATEST" ]; then
    echo "❌ No verification logs found. Make sure the application is running and processing data."
    exit 1
fi

echo "✅ Verification log found!"
echo ""

# Parse JSON (requires jq)
if ! command -v jq &> /dev/null; then
    echo "⚠️  jq not installed. Install it to see detailed verification."
    echo "Raw log: $LATEST"
    exit 0
fi

echo "📊 Phase 1: Trade Classification"
echo "$LATEST" | jq -r '.data.phase1_tradeClassification | "  Volume: \(.volume) | Buy: \(.aggressiveBuyVolume) | Sell: \(.aggressiveSellVolume) | Midpoint: \(.midpointVolume) | Reliability: \(.classificationReliability)"'

echo ""
echo "📊 Phase 2: Temporal Tracking"
echo "$LATEST" | jq -r '.data.phase2_temporalTracking | "  Ticks: \(.tickCount) | TPS: \(.ticksPerSecond) | Acceleration: \(.tickAcceleration)"'

echo ""
echo "📊 Phase 3: Orderbook Depth"
echo "$LATEST" | jq -r '.data.phase3_orderbookDepth | "  Bid Orders: \(.totalBidOrders) | Ask Orders: \(.totalAskOrders) | Iceberg Bid: \(.icebergAtBestBid) | Iceberg Ask: \(.icebergAtBestAsk)"'

echo ""
echo "📊 Phase 4: Trade Size Distribution"
echo "$LATEST" | jq -r '.data.phase4_tradeSize | "  Max: \(.maxTradeSize) | Avg: \(.avgTradeSize) | Large Trades: \(.largeTradeCount)"'

echo ""
echo "📊 Phase 5: OI Correlation"
echo "$LATEST" | jq -r '.data.phase5_oiCorrelation | "  Price at OI: \(.priceAtOIUpdate) | Latency: \(.oiUpdateLatency)ms"'

echo ""
echo "📊 Phase 6: Cross-Stream Latency"
echo "$LATEST" | jq -r '.data.phase6_crossStreamLatency | "  Tick Stale: \(.tickStale) | Max Age: \(.maxDataAge)ms | Reason: \(.stalenessReason)"'

echo ""
echo "📊 Phase 7: Orderbook Dynamics"
echo "$LATEST" | jq -r '.data.phase7_orderbookDynamics | "  Updates: \(.orderbookUpdateCount) | Spread Volatility: \(.spreadVolatility)"'

echo ""
echo "📊 Metadata"
echo "$LATEST" | jq -r '.data.metadata | "  ScripCode: \(.scripCode) | Has OB: \(.hasOrderbook) | Has OI: \(.hasOI) | VPIN: \(.vpin) | Replay: \(.isReplay)"'

echo ""
echo "=== VERIFICATION COMPLETE ==="
```

Make it executable:
```bash
chmod +x verify_all_phases.sh
./verify_all_phases.sh
```

## Expected Results

### ✅ All Phases Working:
- All 7 phases have non-null values (where applicable)
- `midpointVolume` ≤ `volume` (not 2x)
- `tickStale` = false for fresh data
- `classificationReliability` > 0.7 (for instruments with BBO)
- All timestamps are valid

### ❌ Issues to Watch For:
- `midpointVolume` = 2x `volume` → Double counting bug (should be fixed)
- `tickStale` = true for fresh data → Staleness bug (should be fixed with replay detection)
- Missing fields → Phase not implemented
- All zeros → Data not flowing

## Next Steps

1. **Run the application** and let it process data
2. **Check the log file** using the commands above
3. **Verify each phase** matches expected values
4. **Report any issues** with specific phase and values

---

**Status:** ✅ Comprehensive verification logging enabled
**Log Location:** `logs/debug.log`
**Log Format:** NDJSON (one JSON object per line)
**Filter:** `grep "ALL-PHASES-VERIFY"` to find verification logs

