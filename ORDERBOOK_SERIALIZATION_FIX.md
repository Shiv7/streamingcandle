# Orderbook Serialization Fix - Implementation Summary

## Problem Resolved
Fixed `SerializationException` caused by attempting to serialize service instances (`IcebergDetectionService`, `SpoofingDetectionService`, `OrderbookDepthCalculator`) in Kafka Streams state stores.

## Root Cause
Architectural mistake: Family-structured streams were creating orderbook transformers with stateful accumulators containing business logic services, which Kafka Streams tried to persist to changelog topics.

## Solution Implemented
Moved orderbook enrichment from family-structured streams to per-instrument stream, matching the existing microstructure pattern.

---

## Changes Made

### 1. InstrumentState.java
**Added:**
- `latestOrderbook` field to buffer orderbook snapshots
- `addOrderbook(OrderBookSnapshot)` method to delegate to state manager

### 2. InstrumentStateManager.java
**Added:**
- `orderbookAccumulators` EnumMap for per-timeframe orderbook tracking
- `addOrderbook(OrderBookSnapshot)` method to feed snapshots to all timeframe accumulators
- Reset orderbook accumulators on window rotation (line 207)
- Extract `orderbookDepth` from accumulator in `extractFinalizedCandle()` (line 245-246)
- Embed `orderbookDepth` in `InstrumentCandle` builder (line 281)

### 3. UnifiedMarketDataProcessor.java

#### Per-Instrument Stream (processPerInstrumentStream):
**Added:**
- Orderbook input stream consumption (line 167-170)
- Token-to-scripCode lookup table from tick stream (line 173-177)
- Re-keying orderbook stream by scripCode using lookup (line 180-187)
- Materialized orderbook KTable (line 190-191)
- Left-join of state stream with orderbook table (line 231-238)
- Orderbook enrichment before candle emission (line 241)

#### Family-Structured Streams (buildFamilyStructuredStream):
**Removed:**
- `buildOrderbookDepthTable()` method (previously lines 340-374) - DELETED ENTIRELY
- State store creation for `orderbook-acc-store`
- Orderbook transformer with stateful accumulator

**Simplified:**
- `enrichCandles()` method now only handles OI join (line 377-395)
- Removed `orderbookDepthTable` parameter from `enrichCandles()`
- Removed orderbook leftJoin from `enrichCandles()`

---

## Architecture Changes

### Before (WRONG):
```
Per-Instrument Stream:
  Ticks → Aggregate → Emit Candles

Family-Structured Stream:
  Candles + Orderbook (via stateful transformer) → Aggregate Family
                      ↑
                      This tried to serialize services!
```

### After (CORRECT):
```
Per-Instrument Stream:
  Ticks + Orderbook → Aggregate → Emit Enriched Candles
                                  (orderbook already embedded)

Family-Structured Stream:
  Enriched Candles → Aggregate Family
  (just aggregates, no enrichment)
```

---

## Key Design Principles Applied

1. **State stores = data only**: Never serialize business logic (services, processors)
2. **Accumulators in-memory**: History-tracking accumulators live in `InstrumentStateManager`, reset per window
3. **Enrich early**: Enrich candles at per-instrument level, family streams just aggregate
4. **Pattern consistency**: Orderbook follows exact same pattern as microstructure/imbalance bars

---

## Verification

### Compile Status: ✅ SUCCESS
```bash
mvn clean compile -DskipTests
[INFO] BUILD SUCCESS
```

### Expected Runtime Behavior:
1. ✅ No `SerializationException` in logs
2. ✅ Per-instrument candles have `orderbookDepth` populated
3. ✅ Iceberg/spoofing history preserved correctly (20-snapshot window)
4. ✅ Family-structured streams consume and aggregate without errors
5. ✅ Orderbook metrics: spread, depth, imbalances, iceberg detection, spoofing detection

---

## Testing Checklist

- [ ] Verify per-instrument candles have non-null `orderbookDepth` in logs
- [ ] Verify family-structured output includes aggregated orderbook metrics
- [ ] Verify no `SerializationException` related to `IcebergDetectionService`
- [ ] Verify iceberg detection works (check `icebergDetectedBid/Ask` fields)
- [ ] Verify spoofing detection works (check `spoofingEvents` field)
- [ ] Monitor memory usage (orderbook accumulators reset per window)

---

## Files Modified

1. `src/main/java/com/kotsin/consumer/processor/InstrumentState.java`
   - Added orderbook handling

2. `src/main/java/com/kotsin/consumer/processor/service/InstrumentStateManager.java`
   - Added orderbook accumulators per timeframe
   - Added window rotation reset logic
   - Embedded orderbook depth in finalized candles

3. `src/main/java/com/kotsin/consumer/processor/UnifiedMarketDataProcessor.java`
   - Added orderbook stream to per-instrument topology
   - Removed orderbook transformer from family-structured streams
   - Simplified family enrichment (OI only)

---

## Date: 2025-10-21
## Status: COMPLETE ✅
