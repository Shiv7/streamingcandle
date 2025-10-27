# New Streaming Candle Architecture - Separate Window Aggregations

## Problem with Current Design

**Current (Broken):**
```
Ticks Stream ────┐
                 ├──> MERGE ──> Window ──> Aggregate ──> Output
Orderbook Stream ┘

Issue: When orderbook timestamp > tick timestamp, stream time advances
       → Ticks become "late" → Dropped → Missing candles
```

## New Design (Proper Fix)

**Proposed:**
```
Ticks Stream ──────> Window ──> OHLCV Candle ────────┐
                                                       │
Orderbook Stream ──> Window ──> Orderbook Signals ────┤──> JOIN ──> Final Candle
                                                       │
OI Stream ─────────> Window ──> OI Metrics ───────────┘

Benefits:
✅ Each stream processes at its own pace
✅ No cross-stream "late" records
✅ Independent event time per stream
✅ Easier to debug and monitor
```

## Implementation Plan

### Step 1: Separate Window Aggregations

**1.1 Ticks → OHLCV Candle**
```java
KStream<String, TickData> ticks
    .groupByKey()
    .windowedBy(TimeWindows.ofSizeAndGrace(Duration.ofMinutes(1), Duration.ofSeconds(10)))
    .aggregate(
        () -> new CandleAccumulator(),
        (key, tick, candle) -> candle.addTick(tick),
        materialized("ticks-candle-store-1m")
    )
    .suppress(untilWindowCloses())
    .toStream()
    .to("intermediate-ohlcv-1m");  // Intermediate topic
```

**1.2 Orderbook → Orderbook Signals**
```java
KStream<String, OrderBookSnapshot> orderbook
    .groupByKey()
    .windowedBy(TimeWindows.ofSizeAndGrace(Duration.ofMinutes(1), Duration.ofSeconds(10)))
    .aggregate(
        () -> new OrderbookDepthAccumulator(),
        (key, ob, depth) -> depth.addOrderbook(ob),
        materialized("orderbook-depth-store-1m")
    )
    .suppress(untilWindowCloses())
    .toStream()
    .to("intermediate-orderbook-1m");  // Intermediate topic
```

**1.3 OI → OI Metrics**
```java
KStream<String, OpenInterest> oi
    .groupByKey()
    .windowedBy(TimeWindows.ofSizeAndGrace(Duration.ofMinutes(1), Duration.ofSeconds(10)))
    .aggregate(
        () -> new OiAccumulator(),
        (key, oi, acc) -> acc.addOi(oi),
        materialized("oi-metrics-store-1m")
    )
    .suppress(untilWindowCloses())
    .toStream()
    .to("intermediate-oi-1m");  // Intermediate topic
```

### Step 2: Join Windowed Results

**2.1 Join Strategy**

All three streams now have:
- Key: `scripCode` (or `token`)
- Window: `[windowStart, windowEnd)`
- Windowed key: `scripCode@windowStart-windowEnd`

**2.2 Join Implementation**

```java
KStream<String, OHLCVCandle> ohlcv = builder.stream("intermediate-ohlcv-1m");
KStream<String, OrderbookSignals> obSignals = builder.stream("intermediate-orderbook-1m");
KStream<String, OIMetrics> oiMetrics = builder.stream("intermediate-oi-1m");

// Option A: Use CoGroup (Kafka Streams 2.4+)
ohlcv.groupByKey()
    .cogroup((key, candle, agg) -> agg.setOhlcv(candle))
    .cogroup(obSignals.groupByKey(), (key, signals, agg) -> agg.setOrderbook(signals))
    .cogroup(oiMetrics.groupByKey(), (key, metrics, agg) -> agg.setOi(metrics))
    .aggregate(() -> new UnifiedCandle())
    .toStream()
    .filter((k, v) -> v.isComplete())  // Has all components
    .to("candle-complete-1m");

// Option B: Use leftJoin (simpler but requires KTable conversion)
KTable<String, OHLCVCandle> ohlcvTable = ohlcv.toTable();
KTable<String, OrderbookSignals> obTable = obSignals.toTable();
KTable<String, OIMetrics> oiTable = oiMetrics.toTable();

ohlcvTable
    .leftJoin(obTable, (candle, signals) -> candle.withOrderbook(signals))
    .leftJoin(oiTable, (candle, metrics) -> candle.withOi(metrics))
    .toStream()
    .to("candle-complete-1m");
```

## Key Changes Required

### 1. New Intermediate Topics

Create topics for each windowed stream:
```bash
# For each timeframe (1m, 2m, 3m, 5m, 15m, 30m)
kafka-topics --create --topic intermediate-ohlcv-1m
kafka-topics --create --topic intermediate-orderbook-1m
kafka-topics --create --topic intermediate-oi-1m
```

### 2. Modified Models

**OHLCVCandle.java** (subset of current InstrumentCandle)
```java
public class OHLCVCandle {
    private String scripCode;
    private Long windowStartMillis;
    private Long windowEndMillis;
    private Double open, high, low, close;
    private Long volume, buyVolume, sellVolume;
    private Double vwap;
    private Integer tickCount;
}
```

**OrderbookSignals.java** (extracted from current)
```java
public class OrderbookSignals {
    private String scripCode;
    private Long windowStartMillis;
    private Long windowEndMillis;
    private Double ofi, vpin, depthImbalance;
    private Double spreadAvg, bidVWAP, askVWAP;
    private Boolean icebergBid, icebergAsk;
    private Integer spoofingCount;
}
```

**OIMetrics.java**
```java
public class OIMetrics {
    private String scripCode;
    private Long windowStartMillis;
    private Long windowEndMillis;
    private Long oiOpen, oiHigh, oiLow, oiClose;
    private Long oiChange;
    private Double oiChangePercent;
}
```

### 3. Refactored Topology

**TopologyConfiguration.java** - Needs major refactoring:
```java
public class TopologyConfiguration {

    public StreamsBuilder createInstrumentTopology() {
        StreamsBuilder builder = new StreamsBuilder();

        for (Timeframe tf : Timeframe.values()) {
            // 1. Build tick aggregation
            buildTickAggregation(builder, tf);

            // 2. Build orderbook aggregation
            buildOrderbookAggregation(builder, tf);

            // 3. Build OI aggregation
            buildOiAggregation(builder, tf);

            // 4. Join all three
            buildFinalJoin(builder, tf);
        }

        return builder;
    }

    private void buildTickAggregation(StreamsBuilder builder, Timeframe tf) {
        // Aggregate ticks independently
    }

    private void buildOrderbookAggregation(StreamsBuilder builder, Timeframe tf) {
        // Aggregate orderbook independently
    }

    private void buildOiAggregation(StreamsBuilder builder, Timeframe tf) {
        // Aggregate OI independently
    }

    private void buildFinalJoin(StreamsBuilder builder, Timeframe tf) {
        // Join OHLCV + Orderbook + OI
    }
}
```

## Benefits of New Design

1. **No Grace Period Issues**: Each stream processes at its own pace
2. **Independent Scaling**: Can scale tick processing separately from orderbook
3. **Easier Testing**: Test each aggregation independently
4. **Clearer Debugging**: Know exactly which stream is causing issues
5. **Resilient to Out-of-Order**: Each stream handles its own lateness
6. **Flexible**: Can add new streams (e.g., news, sentiment) without affecting others

## Migration Strategy

1. **Phase 1**: Implement new topology alongside old one (different app-id)
2. **Phase 2**: Test with historical data replay
3. **Phase 3**: Test with live data in parallel
4. **Phase 4**: Switch over, deprecate old topology

## Configuration Changes

```properties
# New intermediate topics
stream.intermediate.ohlcv.1m=intermediate-ohlcv-1m
stream.intermediate.orderbook.1m=intermediate-orderbook-1m
stream.intermediate.oi.1m=intermediate-oi-1m

# Join timeout (wait for all components)
stream.join.timeout.seconds=5

# Per-stream grace periods (can be different!)
stream.grace.ticks.seconds=10
stream.grace.orderbook.seconds=10
stream.grace.oi.seconds=30  # OI updates slower
```

## Questions to Answer

1. **Join Timeout**: How long to wait if orderbook/OI missing? Emit partial candle?
2. **Key Alignment**: Use scripCode or token as key? Need consistent mapping
3. **Window Alignment**: All streams use same window definition (9:15 base)?
4. **Partial Candles**: Emit candle with only OHLCV if orderbook missing?

## Next Steps

1. Review this design
2. Decide on join strategy (cogroup vs leftJoin)
3. Create intermediate topic schemas
4. Implement Phase 1 (new topology with test app-id)
5. Test with your live data
