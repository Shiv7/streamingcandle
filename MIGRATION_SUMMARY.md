# Migration to New Architecture - Summary

## What Changed

### OLD ARCHITECTURE (Broken)
```
forwardtesting-data ─┐
Orderbook ───────────┼─> MERGE → Window → Aggregate → candle-complete-1m
OpenInterest ────────┘

Problem: When orderbook timestamp > tick timestamp, Kafka Streams considers
         ticks "late" and drops them → Missing candles
```

### NEW ARCHITECTURE (Fixed)
```
forwardtesting-data → Window → OHLCV ──────┐
                                             │
Orderbook ──────────→ Window → Signals ─────┤→ LEFT JOIN → candle-complete-1m
                                             │
OpenInterest ───────→ Window → Metrics ─────┘

Benefits: Each stream processes at its own pace. No cross-stream lateness issues.
```

## Files Created

1. **WindowedOHLCV.java** - Tick aggregation result (OHLCV + imbalance + microstructure)
2. **WindowedOrderbookSignals.java** - Orderbook aggregation result
3. **WindowedOIMetrics.java** - OI aggregation result
4. **TickWindowState.java** - State for tick window aggregation
5. **OrderbookWindowState.java** - State for orderbook window aggregation
6. **OIWindowState.java** - State for OI window aggregation

## Files Modified

1. **TopologyConfiguration.java** - Completely refactored
   - Old version backed up as `TopologyConfiguration.java.backup`
   - New version implements 3 separate window aggregations + LEFT JOIN

2. **application.properties** - Added intermediate topic configuration
   - `stream.intermediate.ohlcv.{1m,2m,3m,5m,15m,30m}`
   - `stream.intermediate.orderbook.{1m,2m,3m,5m,15m,30m}`
   - `stream.intermediate.oi.{1m,2m,3m,5m,15m,30m}`

## How It Works Now

### Phase 1: Independent Window Aggregations

**Tick Aggregation:**
```java
forwardtesting-data
  → CumToDeltaTransformer
  → Window(1m, grace=10s)
  → TickWindowState{CandleAccumulator, MicrostructureAccumulator, ImbalanceBarAccumulator}
  → WindowedOHLCV
  → intermediate-ohlcv-1m
```

**Orderbook Aggregation:**
```java
Orderbook
  → Window(1m, grace=10s)
  → OrderbookWindowState{OrderbookDepthAccumulator}
  → WindowedOrderbookSignals
  → intermediate-orderbook-1m
```

**OI Aggregation:**
```java
OpenInterest
  → Window(1m, grace=30s)  // More grace for slower OI updates
  → OIWindowState
  → WindowedOIMetrics
  → intermediate-oi-1m
```

### Phase 2: LEFT JOIN

```java
intermediate-ohlcv-1m (base)
  LEFT JOIN intermediate-orderbook-1m
  LEFT JOIN intermediate-oi-1m
  → UnifiedWindowMessage
  → candle-complete-1m
```

## Key Benefits

1. **No More Late Records** - Each stream has independent event time
2. **Always Get Candles** - LEFT JOIN ensures OHLCV always emits (even if orderbook/OI missing)
3. **Easier Debugging** - Can inspect intermediate topics
4. **Independent Scaling** - Can tune grace periods per stream type
5. **Resilient** - Orderbook issues won't block tick processing

## Configuration Changes

### Grace Periods (per stream)
- **Ticks**: 10 seconds (low latency needed)
- **Orderbook**: 10 seconds
- **OI**: 30 seconds (updates slower)

### New Intermediate Topics (auto-created by Kafka Streams)
For each timeframe (1m, 2m, 3m, 5m, 15m, 30m):
- `intermediate-ohlcv-{tf}`
- `intermediate-orderbook-{tf}`
- `intermediate-oi-{tf}`

Total: **18 new topics** (3 streams × 6 timeframes)

## Testing Steps

1. **Clean State Stores**
   ```bash
   rm -rf /tmp/kafka-streams/streamingcandle
   ```

2. **Build Project**
   ```bash
   mvn clean package -DskipTests
   ```

3. **Run Application**
   ```bash
   mvn spring-boot:run
   ```

4. **Verify Logs**
   Look for:
   ```
   🗃️ Building NEW ARCHITECTURE: separate window aggregations per stream
   [1m] Building tick aggregation → intermediate-ohlcv-1m
   [1m] Building orderbook aggregation → intermediate-orderbook-1m
   [1m] Building OI aggregation → intermediate-oi-1m
   [1m] Building LEFT JOIN topology → candle-complete-1m
   [1m] OHLCV emitted: scrip=220 vol=1234
   [1m] Orderbook signals emitted: scrip=220
   📤 EMITTING unified: tf=1m scrip=220 vol=1234
   ```

5. **Check Intermediate Topics**
   ```bash
   kafka-console-consumer --bootstrap-server localhost:9092 \
     --topic intermediate-ohlcv-1m --from-beginning
   ```

6. **Monitor Final Output**
   ```bash
   kafka-console-consumer --bootstrap-server localhost:9092 \
     --topic candle-complete-1m --from-beginning
   ```

## Expected Outcome

✅ **No more "late record" warnings**
✅ **All output topics receive data**
✅ **Candles always emitted** (even if orderbook/OI missing for some instruments)
✅ **Stable processing** (no cross-stream time pollution)

## Rollback Plan

If issues occur, restore old topology:
```bash
mv TopologyConfiguration.java.backup TopologyConfiguration.java
# Revert application.properties intermediate topic config
mvn clean package -DskipTests
mvn spring-boot:run
```

## Next Steps After Successful Test

1. Monitor intermediate topic sizes (may need retention policy)
2. Tune grace periods based on actual lateness observed
3. Consider compaction for intermediate topics if needed
4. Update documentation/PRD with new architecture

---

**Status**: ✅ Implementation Complete - Ready for Testing
**Risk Level**: Medium (major refactor, but well-tested pattern)
**Rollback Available**: Yes (backup file created)
