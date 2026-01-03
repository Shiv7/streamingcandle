# Last Known Value Fallback Strategy - Analysis & Implementation Plan

## 📊 Current State Analysis

### Current Architecture

1. **Tick Data**: Windowed KTable (1-minute windows)
   - ✅ Always available (mandatory)
   - ❌ Window expires after grace period

2. **Orderbook Data**: Windowed KTable (1-minute windows)
   - ⚠️ LEFT JOIN - can be null
   - ❌ **Problem**: When orderbook arrives late, window already expired
   - ❌ No fallback mechanism

3. **OI Data**: Non-windowed KTable (latest state store)
   - ✅ Already has "latest" state store
   - ✅ Can be looked up even after window closes
   - ⚠️ **Issue**: Still can be null if OI never arrived for that instrument

### Problem Statement

During replay scenarios:
- Stream time advances 20-60 minutes ahead of window end times
- Late-arriving orderbook/OI data misses their windows
- Result: **Incomplete candles** with missing orderbook/OI metrics
- Impact: OFI, Kyle's Lambda, depth metrics are NULL

---

## 💡 Proposed Solution: "Last Known Value" Fallback

### Concept

When windowed join fails (orderbook missing), fallback to **last known orderbook aggregate** from a non-windowed state store.

### Architecture Changes

```
Current:
  Tick (windowed) + Orderbook (windowed) → LEFT JOIN → may be null

Proposed:
  Tick (windowed) + Orderbook (windowed) → LEFT JOIN
    ↓ (if null)
  Tick (windowed) + Orderbook (latest state store) → FALLBACK
```

---

## 🏗️ Implementation Approach

### Phase 1: Create Latest Orderbook State Store

**Location**: After orderbook aggregation (around line 383)

```java
// NEW: Create non-windowed "latest" orderbook state store
KTable<String, OrderbookAggregate> obLatestTable = orderbooksRepartitioned
    .groupByKey(Grouped.with(Serdes.String(), OrderBookSnapshot.serde()))
    .aggregate(
        OrderbookAggregate::new,
        (key, ob, agg) -> {
            agg.updateWithSnapshot(ob);
            // Update window times to "latest" (not windowed)
            agg.setWindowStartMillis(ob.getReceivedTimestamp());
            agg.setWindowEndMillis(ob.getReceivedTimestamp());
            return agg;
        },
        Materialized.<String, OrderbookAggregate, KeyValueStore<Bytes, byte[]>>as("orderbook-latest-store")
            .withKeySerde(Serdes.String())
            .withValueSerde(OrderbookAggregate.serde())
    );
```

**Effort**: ~30 minutes
- Add KTable aggregation
- Configure state store
- Test state store creation

---

### Phase 2: Modify Join Logic to Use Fallback

**Location**: Replace current LEFT JOIN (line 413-422) with transformer

```java
// REPLACE: Simple LEFT JOIN
KTable<Windowed<String>, TickWithOrderbook> tickCandlesWithOb = tickCandles.leftJoin(...)

// WITH: Transformer that checks fallback
KStream<Windowed<String>, TickWithOrderbook> tickCandlesWithOb = tickCandles
    .toStream()
    .transformValues(
        () -> new ValueTransformerWithKey<Windowed<String>, TickAggregate, TickWithOrderbook>() {
            private ReadOnlyKeyValueStore<String, OrderbookAggregate> obLatestStore;
            
            @Override
            public void init(ProcessorContext context) {
                this.obLatestStore = (ReadOnlyKeyValueStore<String, OrderbookAggregate>) 
                    context.getStateStore("orderbook-latest-store");
            }
            
            @Override
            public TickWithOrderbook transform(Windowed<String> windowedKey, TickAggregate tick) {
                // Try windowed join first (if available)
                OrderbookAggregate ob = null;
                
                // TODO: Get from windowed store if available
                // For now, fallback to latest
                
                // Fallback: Get latest orderbook
                if (ob == null && obLatestStore != null) {
                    String key = windowedKey.key();
                    Object storeValue = obLatestStore.get(key);
                    
                    if (storeValue instanceof ValueAndTimestamp) {
                        ob = ((ValueAndTimestamp<OrderbookAggregate>) storeValue).value();
                    } else if (storeValue instanceof OrderbookAggregate) {
                        ob = (OrderbookAggregate) storeValue;
                    }
                    
                    if (ob != null) {
                        log.debug("[OB-FALLBACK] {} | Using latest orderbook (window expired)", key);
                        // Optionally: Mark as "stale" or "fallback" in metadata
                    }
                }
                
                return new TickWithOrderbook(tick, ob);
            }
        },
        Named.as("ob-fallback-transformer"),
        "orderbook-latest-store"
    );
```

**Challenge**: Windowed join result is not directly accessible in transformer.

**Alternative Approach**: Use `transformValues` on the joined stream:

```java
// Keep existing LEFT JOIN
KTable<Windowed<String>, TickWithOrderbook> tickCandlesWithOb = tickCandles.leftJoin(...);

// Add fallback transformer AFTER join
KStream<Windowed<String>, TickWithOrderbook> tickCandlesWithObStream = tickCandlesWithOb
    .toStream()
    .transformValues(
        () -> new ValueTransformerWithKey<Windowed<String>, TickWithOrderbook, TickWithOrderbook>() {
            private ReadOnlyKeyValueStore<String, OrderbookAggregate> obLatestStore;
            
            @Override
            public void init(ProcessorContext context) {
                this.obLatestStore = (ReadOnlyKeyValueStore<String, OrderbookAggregate>) 
                    context.getStateStore("orderbook-latest-store");
            }
            
            @Override
            public TickWithOrderbook transform(Windowed<String> windowedKey, TickWithOrderbook tickOb) {
                // If orderbook is null, try fallback
                if (tickOb.orderbook == null && obLatestStore != null) {
                    String key = windowedKey.key();
                    Object storeValue = obLatestStore.get(key);
                    
                    OrderbookAggregate fallbackOb = null;
                    if (storeValue instanceof ValueAndTimestamp) {
                        fallbackOb = ((ValueAndTimestamp<OrderbookAggregate>) storeValue).value();
                    } else if (storeValue instanceof OrderbookAggregate) {
                        fallbackOb = (OrderbookAggregate) storeValue;
                    }
                    
                    if (fallbackOb != null) {
                        log.debug("[OB-FALLBACK] {} | Using latest orderbook (window={} - {})", 
                            key, 
                            new Date(windowedKey.window().start()),
                            new Date(windowedKey.window().end()));
                        
                        // Create new TickWithOrderbook with fallback
                        return new TickWithOrderbook(tickOb.tick, fallbackOb);
                    }
                }
                
                return tickOb;
            }
        },
        Named.as("ob-fallback-transformer"),
        "orderbook-latest-store"
    );
```

**Effort**: ~2-3 hours
- Modify join logic
- Add transformer
- Handle ValueAndTimestamp unwrapping
- Add logging
- Test fallback behavior

---

### Phase 3: Improve OI Fallback (Already Good, But Can Enhance)

**Current**: OI already uses state store lookup ✅

**Enhancement**: Add staleness tracking

```java
// In OI transformer (around line 444)
if (oi == null && tickExchType.equals("D")) {
    // Try to find ANY OI for this instrument (even if old)
    // This helps with instruments that have sparse OI updates
    log.warn("[OI-MISS] {} | No OI found (even in latest store)", oiKey);
} else if (oi != null) {
    // Check staleness
    long oiAge = System.currentTimeMillis() - oi.getLastUpdateTimestamp();
    if (oiAge > 300000) { // 5 minutes
        log.debug("[OI-STALE] {} | Using OI that is {} minutes old", oiKey, oiAge / 60000);
    }
}
```

**Effort**: ~30 minutes
- Add staleness logging
- Optional: Add metadata flag for "stale OI"

---

## 📈 Benefits

### 1. **Data Completeness**
- ✅ Orderbook metrics available even when window expired
- ✅ OFI, Kyle's Lambda, depth metrics populated
- ✅ Better data quality for downstream analysis

### 2. **Replay Resilience**
- ✅ Works during fast replay scenarios
- ✅ Handles late-arriving data gracefully
- ✅ Reduces data loss

### 3. **Backward Compatible**
- ✅ Existing logic unchanged
- ✅ Only adds fallback when primary join fails
- ✅ No breaking changes

---

## ⚠️ Trade-offs & Considerations

### 1. **Data Freshness**
- **Risk**: Using "stale" orderbook (from previous window)
- **Mitigation**: 
  - Add metadata flag: `isOrderbookFallback: true`
  - Log when fallback is used
  - Consider: Only use fallback if age < threshold (e.g., 5 minutes)

### 2. **State Store Size**
- **Impact**: Additional state store (memory usage)
- **Mitigation**: 
  - Orderbook aggregates are relatively small
  - Only one per instrument (not per window)
  - Similar to existing OI store (already working)

### 3. **Window Metrics Accuracy**
- **Risk**: Fallback orderbook may not match window timeframe
- **Mitigation**:
  - ✅ **COMPLETED**: Added `orderbookDataTimestamp` and `oiDataTimestamp` to track actual data capture time
  - ✅ **COMPLETED**: Added `isOrderbookFallback` and `isOIFallback` flags to indicate fallback usage
  - Document that fallback metrics are "approximate"
  - Emitted message now shows: `windowEndMillis` (window time) vs `orderbookDataTimestamp` (actual data time)
  - Consider: Only use fallback for "summary" metrics, not time-sensitive ones

### 4. **Complexity**
- **Impact**: More complex join logic
- **Mitigation**:
  - Well-documented code
  - Clear logging for debugging
  - Fallback is opt-in (only when primary fails)

---

## 🎯 Recommended Implementation Strategy

### Option A: Conservative (Recommended)
1. ✅ Create latest orderbook store
2. ✅ Add fallback transformer
3. ✅ Add metadata flag: `isOrderbookFallback`
4. ✅ Only use fallback if orderbook age < 5 minutes
5. ✅ Comprehensive logging

**Effort**: ~4-5 hours
**Risk**: Low
**Benefit**: High

### Option B: Aggressive
1. ✅ Same as Option A
2. ✅ Always use latest (no age check)
3. ✅ Use fallback even when windowed join succeeds (if latest is newer)

**Effort**: ~5-6 hours
**Risk**: Medium (data freshness concerns)
**Benefit**: Very High (maximum completeness)

### Option C: Minimal
1. ✅ Only improve OI fallback (already good)
2. ❌ Skip orderbook fallback (too complex)

**Effort**: ~30 minutes
**Risk**: None
**Benefit**: Low (doesn't solve main problem)

---

## ✅ **COMPLETED: Timestamp Metadata Tracking**

**Status**: Timestamp and fallback flag tracking has been implemented in the data model.

**Changes Made**:
1. ✅ Added `orderbookDataTimestamp` (Long) to `InstrumentCandle` - tracks when orderbook data was actually captured
2. ✅ Added `isOrderbookFallback` (Boolean) to `InstrumentCandle` - indicates if orderbook came from fallback
3. ✅ Added `oiDataTimestamp` (Long) to `InstrumentCandle` - tracks when OI data was actually captured
4. ✅ Added `isOIFallback` (Boolean) to `InstrumentCandle` - indicates if OI came from latest store
5. ✅ Updated `InstrumentCandleData` to pass fallback flags from transformer
6. ✅ Updated `buildInstrumentCandle()` to set timestamps from `lastUpdateTimestamp` or `windowEndMillis`

**Emitted Message Now Includes**:
```json
{
  "windowEndMillis": 1767373200000,           // Window end time
  "orderbookDataTimestamp": 1767372600000,    // When orderbook was actually captured
  "isOrderbookFallback": true,                // true if from latest store
  "oiDataTimestamp": 1767372000000,           // When OI was actually captured
  "isOIFallback": true                        // true if from latest store
}
```

This allows downstream consumers to:
- Know if data is from fallback (approximate) vs windowed (exact)
- Calculate data age: `windowEndMillis - orderbookDataTimestamp`
- Make informed decisions about data quality

---

## 📝 Implementation Checklist

### Phase 1: Latest Orderbook Store (✅ COMPLETED)
- [x] Add `obLatestTable` KTable aggregation
- [x] Configure state store: `orderbook-latest-store`
- [x] Add debug logging for latest store updates
- [ ] Test: Verify store is populated during runtime
- [ ] Test: Verify store persists across restarts

### Phase 2: Fallback Transformer (✅ COMPLETED)
- [x] Add `obLatestStore` field to existing OI transformer
- [x] Access `orderbook-latest-store` in transformer initialization
- [x] Handle `ValueAndTimestamp` unwrapping for orderbook
- [x] Add fallback logic (check if `tickOb.orderbook == null`)
- [x] **Set `isOrderbookFallback = true` when fallback is used**
- [x] **Use `orderbookDataTimestamp` from fallback orderbook**
- [x] Add logging: `[OB-FALLBACK-SUCCESS]`, `[OB-FALLBACK-NULL]`, `[OB-FALLBACK-ERROR]` messages with timestamps
- [x] Register state store with transformer
- [x] Compile successfully
- [ ] Test: Verify fallback works when window expired during replay

### Phase 3: Metadata & Timestamps (✅ COMPLETED)
- [x] Add `isOrderbookFallback` flag to `InstrumentCandle`
- [x] Add `orderbookDataTimestamp` to track when orderbook was actually captured
- [x] Add `isOIFallback` flag to `InstrumentCandle`
- [x] Add `oiDataTimestamp` to track when OI was actually captured
- [x] Update `InstrumentCandleData` to pass fallback flags
- [x] Update `buildInstrumentCandle` to set timestamp metadata
- [ ] Add staleness check (optional: only use if age < 5 min)
- [ ] Update logging to show fallback usage with timestamps

### Phase 4: Testing
- [ ] Unit test: Fallback transformer
- [ ] Integration test: Replay scenario with late orderbook
- [ ] Verify: Metrics populated even when window expired
- [ ] Verify: No performance degradation
- [ ] Monitor: Logs for fallback frequency

---

## 🔢 Effort Estimation

| Task | Effort | Complexity |
|------|--------|------------|
| Create latest OB store | 30 min | Low |
| Fallback transformer | 2-3 hours | Medium |
| Metadata & staleness | 1 hour | Low |
| Testing | 1-2 hours | Medium |
| **Total** | **4-6 hours** | **Medium** |

---

## 🚀 Recommendation

**Go with Option A (Conservative)**:
- ✅ Solves the main problem (missing orderbook during replay)
- ✅ Low risk (only uses fallback when needed)
- ✅ Reasonable effort (4-5 hours)
- ✅ Can enhance later if needed

**Priority**: High (significantly improves data completeness during replay)

**Next Steps**:
1. Review this analysis
2. Approve approach
3. Implement Phase 1 (latest store)
4. Implement Phase 2 (fallback transformer)
5. Test and deploy

---

## 📚 Code References

- Current join logic: `UnifiedInstrumentCandleProcessor.java:413-422`
- OI transformer (reference): `UnifiedInstrumentCandleProcessor.java:432-526`
- Orderbook aggregation: `UnifiedInstrumentCandleProcessor.java:370-383`
- OI latest store (reference): `UnifiedInstrumentCandleProcessor.java:394-410`

