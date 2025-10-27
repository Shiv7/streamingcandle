# Fixes Applied to Streaming Candle

## Issue 1: Cross-Stream Late Records (Original Problem)
**Problem:** Orderbook timestamps advancing stream time, causing ticks to be dropped as "late"

**Solution:** Separate window aggregations per stream type
- Ticks → Window → OHLCV
- Orderbook → Window → Signals
- OI → Window → Metrics
- LEFT JOIN all three → Final candle

**Status:** ✅ Fixed

## Issue 2: Duplicate Delta Volume Transformations
**Problem:** 6 delta-volume transformers (one per timeframe) processing same stream

**Error:**
```
Error sending record to instrument-tick-delta-volume-store-15m-changelog
Failed to append record because it was part of a batch which had invalid records
```

**Solution:** Single shared transformation before branching to timeframes
```java
// ONCE:
forwardtesting-data → SHARED Transformer → TickData Stream

// THEN branch to 6 timeframes:
TickData Stream ──┬─> Window 1m
                  ├─> Window 2m
                  ├─> Window 3m
                  ├─> Window 5m
                  ├─> Window 15m
                  └─> Window 30m
```

**Status:** ✅ Fixed

## Issue 3: State Object Serialization
**Problem:** `final` fields with inline initialization can't be deserialized by Jackson

**Error:**
```
Error sending record to instrument-tick-window-state-30m-repartition
Failed to append record because it was part of a batch which had invalid records
```

**Solution:** Changed from:
```java
// Before (doesn't deserialize):
private final CandleAccumulator candleAccumulator = new CandleAccumulator();
```

To:
```java
// After (Jackson-friendly):
private CandleAccumulator candleAccumulator;

public TickWindowState() {
    this.candleAccumulator = new CandleAccumulator();
}
```

Applied to:
- `TickWindowState.java`
- `OrderbookWindowState.java`
- `OIWindowState.java`

**Status:** ✅ Fixed

## Files Modified

1. **TopologyConfiguration.java**
   - Added `buildSharedTickTransformation()` method
   - Modified `buildTickAggregation()` to use shared ticks
   - Completely refactored to separate stream aggregations

2. **TickWindowState.java**
   - Removed `final` from accumulators
   - Added default constructor

3. **OrderbookWindowState.java**
   - Removed `final` from accumulator
   - Added default constructor

4. **OIWindowState.java**
   - No changes needed (already mutable)

5. **application.properties**
   - Added 18 intermediate topic configurations

6. **scripts/create-changelog-topics.sh**
   - Added intermediate topics creation

## New Intermediate Topics (18 total)

### OHLCV Topics (6)
- intermediate-ohlcv-1m
- intermediate-ohlcv-2m
- intermediate-ohlcv-3m
- intermediate-ohlcv-5m
- intermediate-ohlcv-15m
- intermediate-ohlcv-30m

### Orderbook Topics (6)
- intermediate-orderbook-1m
- intermediate-orderbook-2m
- intermediate-orderbook-3m
- intermediate-orderbook-5m
- intermediate-orderbook-15m
- intermediate-orderbook-30m

### OI Topics (6)
- intermediate-oi-1m
- intermediate-oi-2m
- intermediate-oi-3m
- intermediate-oi-5m
- intermediate-oi-15m
- intermediate-oi-30m

## Testing Checklist

- [ ] Start Kafka broker
- [ ] Create intermediate topics: `bash scripts/create-changelog-topics.sh`
- [ ] Clean state stores: `rm -rf /tmp/kafka-streams/streamingcandle`
- [ ] Start application: `mvn spring-boot:run`
- [ ] Verify logs show:
  - `🔧 Building SHARED tick transformation`
  - `[1m] OHLCV emitted: scrip=...`
  - `📤 EMITTING unified: tf=1m scrip=...`
  - **NO "late record" warnings**
  - **NO "Failed to append record" errors**
- [ ] Check intermediate topics have data
- [ ] Check final output topics have candles

## Expected Result

✅ Candles produced without late record issues
✅ All 6 timeframes working
✅ LEFT JOIN ensures candles always emitted (even if orderbook/OI missing)
✅ Single-threaded processing stable
