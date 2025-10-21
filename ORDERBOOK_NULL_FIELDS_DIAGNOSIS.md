# Orderbook Null Fields Diagnosis

## Symptoms
Future (scripCode: 52503) has `orderbookDepth` with all null fields except:
- `isComplete: false` 
- `valid: false`

While equity and options have fully populated orderbook depth data.

## Kafka Data Shows
The orderbook message for token 52503 EXISTS and is well-formed with:
- bids: 5 levels with Price, Quantity, NumberOfOrders
- asks: 5 levels with Price, Quantity, NumberOfOrders  
- TBidQ: 12250
- TOffQ: 17875

## Root Cause Analysis

### Issue 1: Token-to-ScripCode Join Was Failing (FIXED ✅)
**Problem**: Orderbook was being dropped because token lookup returned null when no ticks had arrived yet.

**Fix Applied**: Simplified orderbook stream to match OI pattern - directly use token as key without lookup table.

```java
// OLD (BROKEN):
KStream → leftJoin(tokenToScripCode) → filter → drops future orderbook

// NEW (FIXED):
KStream → selectKey(by token) → toTable → works!
```

### Issue 2: Orderbook Not Reaching Accumulator (SUSPECTED)

**Hypothesis**: Even after the join fix, the orderbook might not be reaching the `InstrumentStateManager` for futures.

**Possible Causes**:
1. **Timing**: Orderbook arrives after candle window closes
2. **Key Mismatch**: Despite fix, join still not matching
3. **Validation**: `orderbook.isValid()` returning false
4. **Accumulator State**: Orderbook accumulator not initialized or reset prematurely

## Debug Strategy

### Step 1: Add Logging to Verify Join
In `UnifiedMarketDataProcessor.java` line 231-238:

```java
.leftJoin(orderbookTable,
    (state, orderbook) -> {
        log.info("🔍 OB JOIN: scripCode={} obToken={} obValid={} hasBids={}", 
            state.getScripCode(), 
            orderbook != null ? orderbook.getToken() : "null",
            orderbook != null ? orderbook.isValid() : "null",
            orderbook != null && orderbook.getBids() != null ? orderbook.getBids().size() : 0);
        if (orderbook != null && orderbook.isValid()) {
            state.addOrderbook(orderbook);
        }
        return state;
    });
```

### Step 2: Add Logging to InstrumentStateManager
In `InstrumentStateManager.addOrderbook()`:

```java
public void addOrderbook(com.kotsin.consumer.model.OrderBookSnapshot orderbook) {
    if (orderbook == null || !orderbook.isValid()) {
        log.warn("⚠️ OB INVALID: scripCode={} ob={} valid={}", 
            scripCode, orderbook != null, orderbook != null ? orderbook.isValid() : false);
        return;
    }
    
    log.info("✅ OB ADD: scripCode={} token={} bids={} asks={}", 
        scripCode, orderbook.getToken(), 
        orderbook.getBids() != null ? orderbook.getBids().size() : 0,
        orderbook.getAsks() != null ? orderbook.getAsks().size() : 0);
    
    // Update all timeframe accumulators with latest snapshot
    for (com.kotsin.consumer.processor.OrderbookDepthAccumulator acc : orderbookAccumulators.values()) {
        acc.addOrderbook(orderbook);
    }
}
```

### Step 3: Verify parseDetails() Output
In `OrderBookSnapshot.parseDetails()`:

```java
public void parseDetails() {
    log.debug("📋 Parsing OB: token={} bids={} asks={} bidRate={} offRate={}", 
        token,
        bids != null ? bids.size() : 0,
        asks != null ? asks.size() : 0,
        bidRate != null ? bidRate.size() : 0,
        offRate != null ? offRate.size() : 0);
    
    // ... existing parsing logic ...
    
    log.debug("📋 Parsed OB: token={} allBids={} allAsks={}", 
        token,
        allBids != null ? allBids.size() : 0,
        allAsks != null ? allAsks.size() : 0);
}
```

## Expected Findings

If logs show:
1. ✅ **Join succeeds**: "OB JOIN: scripCode=52503 obToken=52503 obValid=true hasBids=5"
   → Problem is in accumulator or extraction

2. ❌ **Join fails**: "OB JOIN: scripCode=52503 obToken=null"
   → Key mismatch still exists despite fix

3. ❌ **Validation fails**: "OB INVALID: scripCode=52503 ob=true valid=false"
   → Check `isValid()` logic - might be too strict

4. ✅ **Reaches accumulator**: "OB ADD: scripCode=52503 token=52503 bids=5 asks=5"
   → Problem is in `OrderbookDepthAccumulator.toOrderbookDepthData()`

## Next Steps

1. Enable debug logging: `logging.level.com.kotsin.consumer=DEBUG`
2. Deploy and observe logs for token 52503
3. Based on findings, apply targeted fix
