# 🔬 Comprehensive Bug Analysis: Trading Signals Pipeline

**Date**: 2025-12-25  
**Scope**: End-to-end data flow from raw ticks to trading-signals topic

---

## Executive Summary

After deep code review and live data analysis, we identified **6 critical bugs** that cause zero values in the final trading signals. The primary issue is that orderbook depth data is not being preserved through the aggregation pipeline, causing VCP validation to fail.

---

## 📊 Data Flow Analysis

```
┌─────────────────┐     ┌────────────────────┐     ┌─────────────────┐
│ forwardtesting- │     │ candle-ohlcv-1m    │     │ candle-ohlcv-5m │
│ data (raw ticks)│────►│ (1m aggregation)   │────►│ (cascaded)      │
└─────────────────┘     └────────────────────┘     └─────────────────┘
                                                            │
┌─────────────────┐     ┌────────────────────┐              │
│ Orderbook (raw) │────►│ orderbook-signals- │              │
│ snapshots       │     │ 5m (aggregated)    │              │
└─────────────────┘     └────────────────────┘              │
                                │                           │
                                ▼                           ▼
                        ┌───────────────────────────────────────┐
                        │     unified-candle-5m (JOIN)          │
                        │  ← BUG #1: bidDepthSnapshot empty     │
                        │  ← BUG #2: kyleLambda = 0             │
                        └───────────────────────────────────────┘
                                        │
                                        ▼
                        ┌───────────────────────────────────────┐
                        │     VCPCalculator                     │
                        │  ← BUG #3: obValidation = 0           │
                        │  ← BUG #3: compositeScore *= 0        │
                        └───────────────────────────────────────┘
                                        │
                                        ▼
                        ┌───────────────────────────────────────┐
                        │     vcp-combined (MTVCPOutput)        │
                        │  ← BUG #4: currentPrice = 0           │
                        │  ← BUG #4: microprice = 0             │
                        └───────────────────────────────────────┘
                                        │
                                        ▼
                        ┌───────────────────────────────────────┐
                        │     trading-signals (TradingSignal)   │
                        │  ← All bugs propagate here            │
                        └───────────────────────────────────────┘
```

---

## 🐛 Bug Catalog

### BUG #1: Empty `bidDepthSnapshot` / `askDepthSnapshot` in UnifiedCandle

**Location**: `UnifiedCandle.java` lines 177-183

**Problem**:
```java
// CURRENT (BROKEN):
if (orderbook.getPrevBidDepth() != null) {
    builder.bidDepthSnapshot(new HashMap<>(orderbook.getPrevBidDepth()));
}
```

**Root Cause**:
- `OrderbookAggregate.prevBidDepth` is used internally for OFI calculation
- It stores the **previous** snapshot's depth, not the **current** one
- When window closes and aggregate is emitted, `prevBidDepth` is stale/empty
- The **current** depth is only held locally in `updateWithSnapshot()` and discarded

**Evidence**:
```json
// unified-candle-5m:
{ "bidDepthSnapshot": 0, "askDepthSnapshot": 0 }
```

**Impact**:
- VCPCalculator.validateWithOrderbook() receives empty depth maps
- `depthAtCluster = 0` → `obValidation = 0/avgDepth = 0`

---

### BUG #2: `kyleLambda` Always Zero

**Location**: `OrderbookAggregate.java` line 56

**Problem**:
```java
private static final int LAMBDA_MIN_OBSERVATIONS = 30;  // Need 30 updates
// ...
if (updatesSinceLastLambdaCalc >= config.lambdaFreq && lambdaObsCount >= config.lambdaMinObs) {
    calculateKyleLambda();  // Only called if 30+ observations
}
```

**Root Cause**:
- Kyle's Lambda requires **30 orderbook updates within a single window**
- Most windows (especially 5m) only receive 5-20 snapshots
- Lambda never gets calculated → stays at initial value of 0.0

**Evidence**:
```json
// All orderbook-signals topics:
{ "kyleLambda": 0.0 }  // 100% of samples
```

**Impact**:
- VCPCalculator.calculatePenetrationDifficulty() uses kyleLambda=0
- `breakoutDifficulty` calculations are meaningless

---

### BUG #3: `compositeScore` Always Zero (VCPCluster)

**Location**: `VCPCluster.java` line 127

**Problem**:
```java
public void calculateCompositeScore() {
    double score = strength;
    double ofiModifier = 1.0 + 0.2 * Math.abs(ofiBias);
    score *= ofiModifier;

    // BUG: When obValidation = 0, entire score becomes 0!
    double obModifier = Math.min(obValidation, 2.0);  // obValidation = 0!
    score *= obModifier;  // score = score * 0 = 0

    score *= oiAdjustment;
    score *= proximity;
    this.compositeScore = Math.min(score, 1.0);  // Always 0
}
```

**Root Cause**:
- `obValidation` is set by `VCPCalculator.validateWithOrderbook()`
- That function uses `bidDepthSnapshot/askDepthSnapshot` from UnifiedCandle
- Those are empty (BUG #1) → `obValidation = 0`
- Multiplying by 0 zeroes out the entire score

**Evidence**:
```json
// vcp-combined:
{
  "clusters": [
    { "strength": 0.86, "obValidation": 0.0, "compositeScore": 0.0 },
    { "strength": 0.72, "obValidation": 0.0, "compositeScore": 0.0 }
  ]
}
```

**Impact**:
- `vcpCombinedScore = 0` (sum of compositeScores)
- `supportScore = 0`, `resistanceScore = 0`

---

### BUG #4: `currentPrice` and `microprice` Not Set in MTVCPOutput

**Location**: `VCPProcessor.java` lines in `buildCombinedOutput`

**Problem**:
The `buildCombinedOutput()` method in VCPCalculator builds MTVCPOutput but never sets:
- `currentPrice`
- `microprice`

**Evidence**:
```json
// vcp-combined:
{ "currentPrice": 0.0, "microprice": 0.0, "atr": 17.92 }  // atr is set, price is not
```

**Root Cause**:
The MTVCPOutput builder in `buildCombinedOutput()` doesn't pull currentPrice from the input candle data.

**Impact**:
- TradingSignal.from() reads `vcp.getCurrentPrice()` → gets 0
- Any downstream calculations using currentPrice are broken

---

### BUG #5: ScripCode Format Mismatch in VCP Join

**Location**: `TradingSignalProcessor.java` line 126

**Problem**:
```java
// VCP uses composite key: "N:C:23650" or "M:D:467741"
// IPU uses simple key: "23650" or "467741"
```

VCP and IPU topics have different key formats:
- `vcp-combined`: `"N:C:23650"` (with exchange prefix)
- `ipu-signals-5m`: `"23650"` (just scripCode)

This may cause join failures for some instruments.

**Impact**:
- Some valid signals may be missed due to key mismatch in left join

---

### BUG #6: Missing OI Data for NSE Cash Instruments

**Location**: `UnifiedCandleProcessor.java` join topology

**Problem**:
- NSE Cash (N:C) instruments don't have Open Interest
- OI join returns null → oiChange is null

**Impact**:
- Minor: OI-related calculations don't apply for cash equities
- This is **expected behavior** for cash, not a bug per se

---

## 📋 Fix Implementation Plan

### Fix #1: Store Current Depth in OrderbookAggregate

**File**: `OrderbookAggregate.java`

Add fields for current depth:
```java
// ADD: Current depth snapshot (not transient, will be serialized)
private Map<Double, Integer> currentBidDepth = new HashMap<>();
private Map<Double, Integer> currentAskDepth = new HashMap<>();
```

Update `updateWithSnapshot()`:
```java
// Store CURRENT depth for emission
this.currentBidDepth = buildDepthMap(orderbook.getAllBids());
this.currentAskDepth = buildDepthMap(orderbook.getAllAsks());

// Also update prev for next OFI calculation
prevBidDepth = currentBidDepth;
prevAskDepth = currentAskDepth;
```

Add getters:
```java
public Map<Double, Integer> getCurrentBidDepth() { return currentBidDepth; }
public Map<Double, Integer> getCurrentAskDepth() { return currentAskDepth; }
```

---

### Fix #2: Update UnifiedCandle.from() to Use Current Depth

**File**: `UnifiedCandle.java`

Change:
```java
// BEFORE (BUG):
builder.bidDepthSnapshot(new HashMap<>(orderbook.getPrevBidDepth()));

// AFTER (FIX):
if (orderbook.getCurrentBidDepth() != null) {
    builder.bidDepthSnapshot(new HashMap<>(orderbook.getCurrentBidDepth()));
}
if (orderbook.getCurrentAskDepth() != null) {
    builder.askDepthSnapshot(new HashMap<>(orderbook.getCurrentAskDepth()));
}
```

---

### Fix #3: Add Fallback in VCPCluster.calculateCompositeScore()

**File**: `VCPCluster.java`

Change:
```java
// BEFORE (BUG):
double obModifier = Math.min(obValidation, 2.0);

// AFTER (FIX): Default to 1.0 when no orderbook validation available
double obModifier = obValidation > 0 ? Math.min(obValidation, 2.0) : 1.0;
```

---

### Fix #4: Set currentPrice and microprice in MTVCPOutput

**File**: `VCPCalculator.java` in `buildCombinedOutput()`

Add:
```java
// Get from the most recent candle in history
UnifiedCandle current = result5m.getCurrentCandle();  // Need to add this field
if (current != null) {
    output.setCurrentPrice(current.getClose());
    output.setMicroprice(current.getMicroprice());
}
```

Alternative: Pass current candle to `buildCombinedOutput()` as a parameter.

---

### Fix #5: Lower kyleLambda Threshold

**File**: `OrderbookAggregate.java`

Change:
```java
// BEFORE:
private static final int LAMBDA_MIN_OBSERVATIONS = 30;

// AFTER: More realistic for 5m windows
private static final int LAMBDA_MIN_OBSERVATIONS = 10;
```

Or make it configurable per timeframe.

---

## 🧪 Validation Commands

After fixes, verify with:

```bash
# Check depth snapshots in unified-candle
kcat -b 13.203.60.173:9094 -t unified-candle-5m -C -c 1 -o end -e | \
  jq '{bidDepthSnapshot: (.bidDepthSnapshot | length), askDepthSnapshot: (.askDepthSnapshot | length)}'

# Check obValidation in VCP clusters
kcat -b 13.203.60.173:9094 -t vcp-combined -C -c 1 -o end -e | \
  jq '.clusters[] | {price, obValidation, compositeScore}'

# Check currentPrice in trading-signals
kcat -b 13.203.60.173:9094 -t trading-signals -C -c 1 -o end -e | \
  jq '{scripCode, signal, currentPrice, microprice, vcpCombinedScore}'
```

---

## 📚 Learnings

### 1. Aggregation Window Lifecycle
When using Kafka Streams windowed aggregation, internal state fields (`prevBidDepth`) may contain stale data at window close time. Always maintain a separate "current" field for emission.

### 2. Multiplicative Score Components
Composite scores that multiply components together are fragile - a single 0 value zeroes out everything. Always add fallback defaults or use additive scoring with weights.

### 3. Key Format Consistency
When joining KTables from different sources, ensure key formats match exactly. Use a consistent naming convention across all topics.

### 4. Observation Thresholds
Statistical calculations requiring minimum observations (like Kyle's Lambda) need thresholds calibrated to actual data frequency. 30 observations per 5 minutes may be unrealistic.

### 5. End-to-End Testing
Always verify data at each pipeline stage, not just the final output. Bugs in early stages cascade through the entire pipeline.

---

## Files to Modify

| File | Bug(s) | Change Summary |
|------|--------|----------------|
| `OrderbookAggregate.java` | #1, #5 | Add currentBidDepth/currentAskDepth fields and getters |
| `UnifiedCandle.java` | #1 | Use getCurrentBidDepth() instead of getPrevBidDepth() |
| `VCPCluster.java` | #3 | Add fallback when obValidation is 0 |
| `VCPCalculator.java` | #4 | Set currentPrice/microprice in buildCombinedOutput() |
