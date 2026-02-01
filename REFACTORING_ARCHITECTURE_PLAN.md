# COMPLETE REFACTORING ARCHITECTURE PLAN

## StreamingCandle v2.0 - MongoDB + Redis + Stateful Strategies

**Date:** 2026-01-31
**Status:** DESIGN DOCUMENT

---

## TABLE OF CONTENTS

1. [Architecture Overview](#1-architecture-overview)
2. [MongoDB Schema Design](#2-mongodb-schema-design)
3. [Higher Timeframe Strategy (30m, 1h, 1d)](#3-higher-timeframe-strategy)
4. [State Management for Strategies](#4-state-management-for-strategies)
5. [Strategy Layer Design](#5-strategy-layer-design)
6. [Redis Caching Strategy](#6-redis-caching-strategy)
7. [Implementation Phases](#7-implementation-phases)

---

## 1. ARCHITECTURE OVERVIEW

### Current Architecture (Broken)
```
Tick ──┐
       │──→ Complex Kafka Join ──→ InstrumentCandle ──→ FamilyCandle ──→ 10 Timeframes
OB  ───┤                            (290 fields)       (1200 fields)     (10 topics)
       │
OI  ───┘
```

### New Architecture (Clean)
```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    LAYER 1: INDEPENDENT KAFKA AGGREGATORS                    │
│                    (Simple, No Joins, Single Responsibility)                 │
│                                                                              │
│   ┌───────────────┐    ┌───────────────┐    ┌───────────────┐               │
│   │ Tick          │    │ Orderbook     │    │ OI            │               │
│   │ Aggregator    │    │ Aggregator    │    │ Aggregator    │               │
│   │ ───────────── │    │ ───────────── │    │ ───────────── │               │
│   │ Input:        │    │ Input:        │    │ Input:        │               │
│   │ forwardtest   │    │ Orderbook     │    │ OpenInterest  │               │
│   │               │    │               │    │               │               │
│   │ Output:       │    │ Output:       │    │ Output:       │               │
│   │ tick-1m topic │    │ ob-1m topic   │    │ oi-1m topic   │               │
│   │ + MongoDB     │    │ + MongoDB     │    │ + MongoDB     │               │
│   │ + Redis       │    │ + Redis       │    │ + Redis       │               │
│   └───────┬───────┘    └───────┬───────┘    └───────┬───────┘               │
│           │                    │                    │                        │
└───────────┼────────────────────┼────────────────────┼────────────────────────┘
            │                    │                    │
            ▼                    ▼                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    LAYER 2: STORAGE (MongoDB + Redis)                        │
│                                                                              │
│   ┌─────────────────────────────────────────────────────────────────┐       │
│   │                         MONGODB                                  │       │
│   │                                                                  │       │
│   │  ┌────────────────┐  ┌────────────────┐  ┌────────────────┐    │       │
│   │  │ tick_candles   │  │ orderbook_1m   │  │ oi_metrics     │    │       │
│   │  │ (1m only)      │  │                │  │                │    │       │
│   │  └────────────────┘  └────────────────┘  └────────────────┘    │       │
│   │                                                                  │       │
│   │  ┌────────────────────────────────────────────────────────┐    │       │
│   │  │ strategy_state (IPU, VCP, Pivot, Reversal state)       │    │       │
│   │  └────────────────────────────────────────────────────────┘    │       │
│   └─────────────────────────────────────────────────────────────────┘       │
│                                                                              │
│   ┌─────────────────────────────────────────────────────────────────┐       │
│   │                         REDIS (Hot Cache)                        │       │
│   │                                                                  │       │
│   │  • tick:{symbol}:1m:latest         (TTL: 5min)                  │       │
│   │  • tick:{symbol}:1m:history:500    (TTL: 24h, last 500 candles) │       │
│   │  • ob:{symbol}:latest              (TTL: 5min)                  │       │
│   │  • oi:{symbol}:latest              (TTL: 5min)                  │       │
│   │  • pivot:{symbol}:levels           (TTL: 24h)                   │       │
│   │  • vcp:{symbol}:clusters           (TTL: 24h)                   │       │
│   │  • ipu:{symbol}:state              (TTL: 24h)                   │       │
│   └─────────────────────────────────────────────────────────────────┘       │
└─────────────────────────────────────────────────────────────────────────────┘
            │                    │                    │
            ▼                    ▼                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    LAYER 3: QUERY & AGGREGATION SERVICE                      │
│                    (On-Demand Joins, Timeframe Aggregation)                  │
│                                                                              │
│   @Service                                                                   │
│   public class CandleService {                                               │
│                                                                              │
│       // Get unified candle (joins tick + ob + oi)                          │
│       UnifiedCandle getCandle(String symbol, Instant timestamp);            │
│                                                                              │
│       // Get higher timeframe (aggregates from 1m)                          │
│       UnifiedCandle getCandle(String symbol, Timeframe tf, Instant ts);     │
│                                                                              │
│       // Get history for strategy calculation                               │
│       List<UnifiedCandle> getHistory(String symbol, Timeframe tf, int n);   │
│                                                                              │
│       // Get family candle (joins equity + future + options)                │
│       FamilyView getFamily(String symbol, Timeframe tf, Instant ts);        │
│   }                                                                          │
└─────────────────────────────────────────────────────────────────────────────┘
            │
            ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    LAYER 4: STRATEGY PROCESSORS                              │
│                    (Stateful, Query-Based, Scheduled)                        │
│                                                                              │
│   ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐             │
│   │ VCP Strategy    │  │ IPU Strategy    │  │ Pivot Strategy  │             │
│   │ ───────────────││  │ ─────────────── │  │ ─────────────── │             │
│   │ State: MongoDB  │  │ State: MongoDB  │  │ State: MongoDB  │             │
│   │ Cache: Redis    │  │ Cache: Redis    │  │ Cache: Redis    │             │
│   │ Query: Service  │  │ Query: Service  │  │ Query: Service  │             │
│   └─────────────────┘  └─────────────────┘  └─────────────────┘             │
│                                                                              │
│   ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐             │
│   │ FUDKII Strategy │  │ Reversal        │  │ Quant Score     │             │
│   │ ─────────────── │  │ Strategy        │  │ Calculator      │             │
│   │ Needs 50 candles│  │ ─────────────── │  │ ─────────────── │             │
│   │ State: MongoDB  │  │ OFI velocity    │  │ Aggregates all  │             │
│   └─────────────────┘  └─────────────────┘  └─────────────────┘             │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
            │
            ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    LAYER 5: SIGNAL OUTPUT                                    │
│                                                                              │
│   trading-signals-v2 topic                                                   │
│   └─→ Signal Enrichment → Gate Chain → Curated Signals                      │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. MONGODB SCHEMA DESIGN

### 2.1 Tick Candles Collection (1-minute only)

```javascript
// Collection: tick_candles_1m
// Index: { symbol: 1, timestamp: -1 } (compound, descending time)
// Index: { timestamp: -1 } (for cleanup/TTL)
// TTL Index: { timestamp: 1 }, expireAfterSeconds: 7776000 (90 days)

{
  "_id": ObjectId(),
  "symbol": "NIFTY",
  "scripCode": "26000",
  "exchange": "N",
  "exchangeType": "C",
  "instrumentType": "INDEX",  // INDEX, EQUITY, FUTURE, OPTION_CE, OPTION_PE

  // Timing
  "timestamp": ISODate("2026-01-31T10:00:00Z"),
  "windowStart": ISODate("2026-01-31T09:59:00Z"),
  "windowEnd": ISODate("2026-01-31T10:00:00Z"),

  // OHLCV (Core)
  "open": 24150.50,
  "high": 24165.75,
  "low": 24142.00,
  "close": 24158.25,
  "volume": 125000,
  "value": 3019781250.0,  // volume * vwap

  // VWAP
  "vwap": 24158.25,
  "typicalPrice": 24155.33,  // (H+L+C)/3

  // Trade Classification
  "buyVolume": 68000,
  "sellVolume": 52000,
  "midpointVolume": 5000,
  "volumeDelta": 16000,  // buy - sell
  "buyPressure": 0.544,  // buy / total
  "sellPressure": 0.416,

  // VPIN
  "vpin": 0.65,
  "vpinBucketSize": 2500,

  // Volume Profile
  "volumeProfile": {
    "poc": 24155.00,      // Point of Control
    "vah": 24162.50,      // Value Area High
    "val": 24148.00,      // Value Area Low
    "volumeAtPrice": {    // Sparse map, only significant levels
      "24150": 15000,
      "24155": 28000,
      "24160": 22000
    }
  },

  // Imbalance Bars
  "imbalance": {
    "volume": 16000,
    "dollar": 386533000,
    "tickRuns": 45,
    "vibTriggered": false,
    "dibTriggered": false
  },

  // Tick Intensity
  "tickCount": 1250,
  "ticksPerSecond": 20.8,
  "largeTradeCount": 5,

  // Quality
  "quality": "VALID",
  "processingLatencyMs": 45,

  // For Options Only
  "optionData": {
    "strikePrice": null,
    "optionType": null,
    "expiry": null,
    "daysToExpiry": null
  },

  "createdAt": ISODate("2026-01-31T10:00:02Z")
}
```

### 2.2 Orderbook Metrics Collection

```javascript
// Collection: orderbook_metrics_1m
// Index: { symbol: 1, timestamp: -1 }
// TTL Index: 90 days

{
  "_id": ObjectId(),
  "symbol": "NIFTY",
  "scripCode": "26000",
  "timestamp": ISODate("2026-01-31T10:00:00Z"),

  // Order Flow Imbalance
  "ofi": 15000.0,
  "ofiMomentum": 2500.0,  // Change from previous

  // Kyle's Lambda (Price Impact)
  "kyleLambda": 0.00015,

  // Microprice
  "microprice": 24158.75,

  // Spread Metrics
  "bidAskSpread": 0.25,
  "spreadVolatility": 0.05,
  "tightSpreadPercent": 0.85,

  // Depth Analysis
  "depthImbalance": 0.15,  // (bid - ask) / (bid + ask)
  "weightedDepthImbalance": 0.12,
  "avgBidDepth": 50000,
  "avgAskDepth": 43000,
  "bidDepthSlope": -0.02,
  "askDepthSlope": -0.018,
  "depthConcentration": 0.45,

  // Anomaly Detection
  "spoofingCount": 0,
  "icebergDetected": false,
  "cancelRate": 0.12,

  // Update Count
  "updateCount": 850,
  "lastUpdateTimestamp": ISODate("2026-01-31T09:59:58Z"),

  "createdAt": ISODate("2026-01-31T10:00:02Z")
}
```

### 2.3 OI Metrics Collection

```javascript
// Collection: oi_metrics_1m
// Index: { symbol: 1, timestamp: -1 }
// TTL Index: 90 days

{
  "_id": ObjectId(),
  "symbol": "NIFTY",
  "scripCode": "26009",  // Future scripCode
  "timestamp": ISODate("2026-01-31T10:00:00Z"),

  // OI Data
  "openInterest": 12500000,
  "oiChange": 125000,
  "oiChangePercent": 1.01,

  // OI Interpretation (based on price + OI change)
  "oiInterpretation": "LONG_BUILDUP",  // LONG_BUILDUP, SHORT_COVERING, SHORT_BUILDUP, LONG_UNWINDING
  "oiInterpretationConfidence": 0.85,

  // Velocity
  "oiVelocity": 2500,  // Change per minute

  // Previous Close (for daily change)
  "previousDayOI": 12375000,
  "dailyOIChange": 125000,
  "dailyOIChangePercent": 1.01,

  "createdAt": ISODate("2026-01-31T10:00:02Z")
}
```

### 2.4 Strategy State Collection

```javascript
// Collection: strategy_state
// Index: { strategyType: 1, symbol: 1 }
// This is the KEY collection for stateful strategies

{
  "_id": ObjectId(),
  "strategyType": "VCP",  // VCP, IPU, PIVOT, REVERSAL, FUDKII
  "symbol": "NIFTY",
  "timeframe": "5m",
  "updatedAt": ISODate("2026-01-31T10:00:00Z"),

  // ==== VCP State ====
  "vcp": {
    "clusters": [
      {
        "priceLevel": 24150.0,
        "volumeConcentration": 0.35,
        "touchCount": 5,
        "firstTouch": ISODate("2026-01-30T09:15:00Z"),
        "lastTouch": ISODate("2026-01-31T09:45:00Z"),
        "isSupport": true,
        "isResistance": false,
        "strength": 0.85
      }
    ],
    "runway": {
      "hasRunway": true,
      "distanceToNextCluster": 50.0,
      "runwayPercent": 0.21
    },
    "lastCalculated": ISODate("2026-01-31T10:00:00Z")
  },

  // ==== IPU State ====
  "ipu": {
    "score": 7.5,
    "direction": "BULLISH",
    "momentum": 0.65,
    "components": {
      "volumeScore": 8.0,
      "priceActionScore": 7.0,
      "institutionalProxy": 0.75
    },
    "history": [  // Last 10 readings for momentum
      { "timestamp": ISODate(), "score": 7.2 },
      { "timestamp": ISODate(), "score": 7.5 }
    ],
    "lastCalculated": ISODate("2026-01-31T10:00:00Z")
  },

  // ==== Pivot State ====
  "pivot": {
    "levels": {
      "daily": {
        "r3": 24350.0,
        "r2": 24280.0,
        "r1": 24215.0,
        "pivot": 24150.0,
        "s1": 24085.0,
        "s2": 24020.0,
        "s3": 23950.0
      },
      "weekly": {
        "r3": 24500.0,
        // ...
      }
    },
    "retestHistory": [
      {
        "level": "s1",
        "price": 24085.0,
        "retestTime": ISODate("2026-01-31T09:30:00Z"),
        "bounced": true,
        "bouncePercent": 0.15
      }
    ],
    "currentNearestLevel": "pivot",
    "distanceToNearest": 8.25,
    "distancePercent": 0.034,
    "lastCalculated": ISODate("2026-01-31T10:00:00Z")
  },

  // ==== Reversal State ====
  "reversal": {
    "previousOfi": 12000.0,
    "ofiVelocity": 3000.0,
    "ofiAcceleration": 500.0,
    "exhaustionDetected": false,
    "exhaustionType": "NONE",
    "reversalScore": 3.5,
    "reversalSignals": ["VOLUME_SPIKE"],
    "lastCalculated": ISODate("2026-01-31T10:00:00Z")
  },

  // ==== FUDKII State (needs 50 candle history) ====
  "fudkii": {
    "candleHistory": [
      // References to last 50 candle IDs (not full candles)
      { "timestamp": ISODate(), "close": 24150.0, "volume": 125000 }
      // ... 49 more
    ],
    "currentScore": 6.8,
    "components": {
      "f": 0.7,  // Flow
      "u": 0.8,  // Urgency
      "d": 0.6,  // Dominance
      "k": 0.7,  // Kurtosis
      "i1": 0.65, // Institutional 1
      "i2": 0.72  // Institutional 2
    },
    "lastCalculated": ISODate("2026-01-31T10:00:00Z")
  }
}
```

### 2.5 MongoDB Indexes Strategy

```javascript
// tick_candles_1m indexes
db.tick_candles_1m.createIndex({ "symbol": 1, "timestamp": -1 });
db.tick_candles_1m.createIndex({ "timestamp": 1 }, { expireAfterSeconds: 7776000 }); // 90 days TTL
db.tick_candles_1m.createIndex({ "instrumentType": 1, "timestamp": -1 });

// orderbook_metrics_1m indexes
db.orderbook_metrics_1m.createIndex({ "symbol": 1, "timestamp": -1 });
db.orderbook_metrics_1m.createIndex({ "timestamp": 1 }, { expireAfterSeconds: 7776000 });

// oi_metrics_1m indexes
db.oi_metrics_1m.createIndex({ "symbol": 1, "timestamp": -1 });
db.oi_metrics_1m.createIndex({ "timestamp": 1 }, { expireAfterSeconds: 7776000 });

// strategy_state indexes
db.strategy_state.createIndex({ "strategyType": 1, "symbol": 1 }, { unique: true });
db.strategy_state.createIndex({ "symbol": 1 });
db.strategy_state.createIndex({ "updatedAt": 1 });
```

---

## 3. HIGHER TIMEFRAME STRATEGY (30m, 1h, 1d)

### Key Decision: On-Demand Aggregation vs Pre-Computed

| Approach | Pros | Cons | When to Use |
|----------|------|------|-------------|
| **On-Demand** | No storage duplication, always fresh | Compute cost per request | Real-time queries, low QPS |
| **Pre-Computed** | Fast reads, no compute | Storage cost, staleness | High QPS, dashboards |
| **Hybrid** | Best of both | Complexity | Production systems |

### Recommended: Hybrid Approach

```
┌─────────────────────────────────────────────────────────────────────┐
│                    TIMEFRAME STRATEGY                                │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  1-MINUTE CANDLES (Source of Truth)                          │   │
│  │  • Stored in MongoDB (tick_candles_1m)                       │   │
│  │  • Cached in Redis (last 500 per symbol)                     │   │
│  │  • TTL: 90 days in MongoDB                                   │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                              │                                       │
│              ┌───────────────┴───────────────┐                      │
│              │                               │                      │
│              ▼                               ▼                      │
│  ┌────────────────────────┐    ┌────────────────────────┐          │
│  │  ON-DEMAND AGGREGATION │    │  PRE-COMPUTED CACHE    │          │
│  │  (Query Time)          │    │  (Background Job)      │          │
│  │                        │    │                        │          │
│  │  • 2m, 3m (rare use)   │    │  • 5m, 15m (popular)   │          │
│  │  • Custom timeframes   │    │  • 30m, 1h (popular)   │          │
│  │  • Historical replay   │    │  • 4h, 1d (popular)    │          │
│  │                        │    │                        │          │
│  │  Uses MongoDB          │    │  Stored in Redis       │          │
│  │  Aggregation Pipeline  │    │  Updated every 1min    │          │
│  └────────────────────────┘    └────────────────────────┘          │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.1 On-Demand Aggregation Service

```java
@Service
public class TimeframeAggregationService {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * Get candle for any timeframe - hybrid approach
     * 1. Check Redis cache for pre-computed popular timeframes
     * 2. If not found or stale, compute on-demand from 1m candles
     */
    public UnifiedCandle getCandle(String symbol, Timeframe tf, Instant timestamp) {
        // Step 1: Try Redis cache for popular timeframes
        if (isPopularTimeframe(tf)) {
            String cacheKey = String.format("candle:%s:%s:%d", symbol, tf,
                alignToWindow(timestamp, tf).toEpochMilli());
            UnifiedCandle cached = (UnifiedCandle) redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        // Step 2: Aggregate from 1m candles using MongoDB
        return aggregateFromMongoDB(symbol, tf, timestamp);
    }

    /**
     * Get history for strategy calculation
     * Example: VCP needs 50 5-minute candles
     */
    public List<UnifiedCandle> getHistory(String symbol, Timeframe tf, int count) {
        // For popular timeframes, try Redis first
        if (isPopularTimeframe(tf)) {
            String historyKey = String.format("history:%s:%s", symbol, tf);
            List<UnifiedCandle> cached = (List<UnifiedCandle>)
                redisTemplate.opsForList().range(historyKey, 0, count - 1);
            if (cached != null && cached.size() >= count) {
                return cached;
            }
        }

        // Fallback: Aggregate from MongoDB
        return aggregateHistoryFromMongoDB(symbol, tf, count);
    }

    /**
     * MongoDB Aggregation Pipeline for timeframe aggregation
     */
    private UnifiedCandle aggregateFromMongoDB(String symbol, Timeframe tf, Instant timestamp) {
        Instant windowStart = alignToWindow(timestamp, tf);
        Instant windowEnd = windowStart.plus(tf.getDuration());

        Aggregation agg = Aggregation.newAggregation(
            // Match 1m candles in the window
            Aggregation.match(Criteria.where("symbol").is(symbol)
                .and("timestamp").gte(windowStart).lt(windowEnd)),

            // Sort by timestamp
            Aggregation.sort(Sort.Direction.ASC, "timestamp"),

            // Group and aggregate OHLCV
            Aggregation.group()
                .first("open").as("open")
                .max("high").as("high")
                .min("low").as("low")
                .last("close").as("close")
                .sum("volume").as("volume")
                .sum("value").as("value")
                .sum("buyVolume").as("buyVolume")
                .sum("sellVolume").as("sellVolume")
                .avg("vpin").as("vpin")
                .count().as("candleCount")
        );

        AggregationResults<TickCandleAggResult> results =
            mongoTemplate.aggregate(agg, "tick_candles_1m", TickCandleAggResult.class);

        return convertToUnifiedCandle(results.getUniqueMappedResult(), symbol, tf, windowStart);
    }

    private boolean isPopularTimeframe(Timeframe tf) {
        return tf == Timeframe.M5 || tf == Timeframe.M15 ||
               tf == Timeframe.M30 || tf == Timeframe.H1 ||
               tf == Timeframe.H4 || tf == Timeframe.D1;
    }
}
```

### 3.2 Background Pre-Computation Job

```java
@Service
public class TimeframeCacheWarmer {

    private static final List<Timeframe> POPULAR_TIMEFRAMES =
        List.of(Timeframe.M5, Timeframe.M15, Timeframe.M30,
                Timeframe.H1, Timeframe.H4, Timeframe.D1);

    @Autowired
    private TimeframeAggregationService aggregationService;

    @Autowired
    private SymbolRegistry symbolRegistry;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * Run every minute to pre-compute popular timeframes
     */
    @Scheduled(fixedRate = 60000)
    public void warmCache() {
        Instant now = Instant.now();

        for (String symbol : symbolRegistry.getActiveSymbols()) {
            for (Timeframe tf : POPULAR_TIMEFRAMES) {
                // Only compute if window just closed
                if (isWindowJustClosed(now, tf)) {
                    try {
                        // Aggregate from MongoDB
                        UnifiedCandle candle = aggregationService
                            .aggregateFromMongoDB(symbol, tf, now.minus(tf.getDuration()));

                        // Store in Redis
                        String cacheKey = buildCacheKey(symbol, tf, candle.getTimestamp());
                        redisTemplate.opsForValue().set(cacheKey, candle,
                            Duration.ofHours(24));

                        // Update history list
                        String historyKey = String.format("history:%s:%s", symbol, tf);
                        redisTemplate.opsForList().leftPush(historyKey, candle);
                        redisTemplate.opsForList().trim(historyKey, 0, 499);  // Keep last 500

                    } catch (Exception e) {
                        log.error("Failed to warm cache for {}:{}", symbol, tf, e);
                    }
                }
            }
        }
    }

    private boolean isWindowJustClosed(Instant now, Timeframe tf) {
        long windowMinutes = tf.getMinutes();
        long minuteOfDay = now.atZone(ZoneId.of("Asia/Kolkata"))
            .getMinute() + now.atZone(ZoneId.of("Asia/Kolkata")).getHour() * 60;
        return minuteOfDay % windowMinutes == 0;
    }
}
```

### 3.3 Higher Timeframe for Trend Analysis

```java
@Service
public class TrendAnalysisService {

    @Autowired
    private TimeframeAggregationService aggregationService;

    /**
     * Get multi-timeframe trend analysis
     * Used by strategies to understand higher timeframe context
     */
    public TrendContext getTrendContext(String symbol) {
        // Get candles from multiple timeframes
        UnifiedCandle candle5m = aggregationService.getLatestCandle(symbol, Timeframe.M5);
        UnifiedCandle candle15m = aggregationService.getLatestCandle(symbol, Timeframe.M15);
        UnifiedCandle candle30m = aggregationService.getLatestCandle(symbol, Timeframe.M30);
        UnifiedCandle candle1h = aggregationService.getLatestCandle(symbol, Timeframe.H1);
        UnifiedCandle candle4h = aggregationService.getLatestCandle(symbol, Timeframe.H4);
        UnifiedCandle candle1d = aggregationService.getLatestCandle(symbol, Timeframe.D1);

        // Calculate trend for each timeframe
        Trend trend5m = calculateTrend(aggregationService.getHistory(symbol, Timeframe.M5, 20));
        Trend trend15m = calculateTrend(aggregationService.getHistory(symbol, Timeframe.M15, 20));
        Trend trend30m = calculateTrend(aggregationService.getHistory(symbol, Timeframe.M30, 20));
        Trend trend1h = calculateTrend(aggregationService.getHistory(symbol, Timeframe.H1, 20));
        Trend trend1d = calculateTrend(aggregationService.getHistory(symbol, Timeframe.D1, 20));

        return TrendContext.builder()
            .symbol(symbol)
            .timestamp(Instant.now())
            .trend5m(trend5m)
            .trend15m(trend15m)
            .trend30m(trend30m)
            .trend1h(trend1h)
            .trend1d(trend1d)
            .overallTrend(determineOverallTrend(trend5m, trend15m, trend30m, trend1h, trend1d))
            .trendAlignment(calculateTrendAlignment(trend5m, trend15m, trend30m, trend1h, trend1d))
            .build();
    }

    private Trend calculateTrend(List<UnifiedCandle> candles) {
        if (candles.size() < 3) return Trend.NEUTRAL;

        // Simple: Compare first and last close
        double firstClose = candles.get(candles.size() - 1).getClose();
        double lastClose = candles.get(0).getClose();
        double change = (lastClose - firstClose) / firstClose * 100;

        // Also check EMA alignment
        double ema9 = calculateEMA(candles, 9);
        double ema21 = calculateEMA(candles, 21);

        if (change > 0.5 && ema9 > ema21) return Trend.BULLISH;
        if (change > 1.5 && ema9 > ema21) return Trend.STRONG_BULLISH;
        if (change < -0.5 && ema9 < ema21) return Trend.BEARISH;
        if (change < -1.5 && ema9 < ema21) return Trend.STRONG_BEARISH;
        return Trend.NEUTRAL;
    }
}
```

---

## 4. STATE MANAGEMENT FOR STRATEGIES

### 4.1 Strategy State Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                    STRATEGY STATE MANAGEMENT                         │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │                 MongoDB (Persistent State)                   │    │
│  │                                                              │    │
│  │  • strategy_state collection                                 │    │
│  │  • One document per {strategy, symbol} combination           │    │
│  │  • Contains: VCP clusters, IPU history, Pivot levels, etc.  │    │
│  │  • Updated every minute after strategy calculation           │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                              │                                       │
│                              │ Load on startup                       │
│                              │ Persist after each calculation        │
│                              ▼                                       │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │                 Redis (Working State Cache)                  │    │
│  │                                                              │    │
│  │  • Fast read/write for current state                        │    │
│  │  • TTL: 24 hours                                            │    │
│  │  • Keys: vcp:{symbol}:state, ipu:{symbol}:state, etc.       │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                              │                                       │
│                              │ Read for calculation                  │
│                              ▼                                       │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │                 Strategy Processor                           │    │
│  │                                                              │    │
│  │  1. Load state from Redis (or MongoDB if cold start)        │    │
│  │  2. Query candle history from CandleService                 │    │
│  │  3. Calculate strategy signals                              │    │
│  │  4. Update state in Redis                                   │    │
│  │  5. Persist state to MongoDB (async)                        │    │
│  │  6. Emit signals if triggered                               │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 4.2 VCP Strategy with State

```java
@Service
@Slf4j
public class VCPStrategy {

    @Autowired
    private CandleService candleService;

    @Autowired
    private StrategyStateRepository stateRepository;

    @Autowired
    private RedisTemplate<String, Object> redis;

    @Autowired
    private SignalPublisher signalPublisher;

    private static final Timeframe ANALYSIS_TF = Timeframe.M5;
    private static final int HISTORY_SIZE = 50;

    /**
     * Run VCP analysis for all active symbols
     * Scheduled every 5 minutes (aligned with timeframe)
     */
    @Scheduled(cron = "0 */5 * * * *")  // Every 5 minutes
    public void analyzeAll() {
        for (String symbol : getActiveSymbols()) {
            try {
                VCPResult result = analyze(symbol);
                if (result.isSignalTriggered()) {
                    signalPublisher.publish(result.toSignal());
                }
            } catch (Exception e) {
                log.error("VCP analysis failed for {}", symbol, e);
            }
        }
    }

    /**
     * Analyze single symbol
     */
    public VCPResult analyze(String symbol) {
        // Step 1: Load state (Redis first, MongoDB fallback)
        VCPState state = loadState(symbol);

        // Step 2: Get candle history
        List<UnifiedCandle> candles = candleService.getHistory(symbol, ANALYSIS_TF, HISTORY_SIZE);
        if (candles.size() < 20) {
            return VCPResult.insufficient(symbol);
        }

        // Step 3: Calculate volume clusters
        List<VolumeCluster> clusters = calculateVolumeClusters(candles, state);

        // Step 4: Check for retest/breakout
        UnifiedCandle currentCandle = candles.get(0);
        VCPSignal signal = checkSignal(currentCandle, clusters, state);

        // Step 5: Update state
        state.setClusters(clusters);
        state.setLastClose(currentCandle.getClose());
        state.setLastCalculated(Instant.now());
        saveState(symbol, state);

        // Step 6: Return result
        return VCPResult.builder()
            .symbol(symbol)
            .clusters(clusters)
            .signal(signal)
            .runway(calculateRunway(currentCandle.getClose(), clusters))
            .build();
    }

    private List<VolumeCluster> calculateVolumeClusters(List<UnifiedCandle> candles, VCPState prevState) {
        // Use previous clusters as starting point (incremental update)
        List<VolumeCluster> clusters = new ArrayList<>(prevState.getClusters());

        // Analyze recent candles for volume concentration
        for (UnifiedCandle candle : candles) {
            double price = candle.getTypicalPrice();  // (H+L+C)/3
            long volume = candle.getVolume();

            // Find or create cluster at this price level
            VolumeCluster existingCluster = findNearestCluster(clusters, price, candle.getAtr() * 0.5);

            if (existingCluster != null) {
                // Update existing cluster
                existingCluster.addVolume(volume);
                existingCluster.incrementTouchCount();
                existingCluster.setLastTouch(candle.getTimestamp());
            } else if (isSignificantVolume(volume, candles)) {
                // Create new cluster
                clusters.add(VolumeCluster.builder()
                    .priceLevel(price)
                    .totalVolume(volume)
                    .touchCount(1)
                    .firstTouch(candle.getTimestamp())
                    .lastTouch(candle.getTimestamp())
                    .build());
            }
        }

        // Decay old clusters (reduce strength over time)
        clusters.forEach(c -> c.decay(0.95));

        // Remove weak clusters
        clusters.removeIf(c -> c.getStrength() < 0.3);

        // Sort by strength
        clusters.sort(Comparator.comparing(VolumeCluster::getStrength).reversed());

        // Keep top 10
        return clusters.stream().limit(10).collect(Collectors.toList());
    }

    private VCPState loadState(String symbol) {
        // Try Redis first
        String cacheKey = "vcp:" + symbol + ":state";
        VCPState cached = (VCPState) redis.opsForValue().get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // Fallback to MongoDB
        Optional<StrategyState> mongoState = stateRepository.findByStrategyTypeAndSymbol("VCP", symbol);
        if (mongoState.isPresent()) {
            VCPState state = mongoState.get().getVcp();
            redis.opsForValue().set(cacheKey, state, Duration.ofHours(24));
            return state;
        }

        // New state
        return new VCPState();
    }

    private void saveState(String symbol, VCPState state) {
        // Save to Redis (sync)
        String cacheKey = "vcp:" + symbol + ":state";
        redis.opsForValue().set(cacheKey, state, Duration.ofHours(24));

        // Save to MongoDB (async)
        CompletableFuture.runAsync(() -> {
            stateRepository.upsertVCPState(symbol, state);
        });
    }
}
```

### 4.3 Pivot Retest Strategy with State

```java
@Service
@Slf4j
public class PivotRetestStrategy {

    @Autowired
    private CandleService candleService;

    @Autowired
    private StrategyStateRepository stateRepository;

    @Autowired
    private RedisTemplate<String, Object> redis;

    /**
     * Calculate pivot levels and detect retests
     * Scheduled every minute for real-time detection
     */
    @Scheduled(fixedRate = 60000)
    public void checkRetests() {
        for (String symbol : getActiveSymbols()) {
            try {
                PivotState state = loadState(symbol);
                UnifiedCandle currentCandle = candleService.getLatestCandle(symbol, Timeframe.M1);

                // Check if price is near any pivot level
                PivotRetest retest = checkPivotRetest(currentCandle, state);

                if (retest != null) {
                    // Record retest
                    state.addRetest(retest);

                    // Check for bounce confirmation
                    if (retest.isBounceConfirmed()) {
                        emitSignal(symbol, retest);
                    }
                }

                saveState(symbol, state);

            } catch (Exception e) {
                log.error("Pivot check failed for {}", symbol, e);
            }
        }
    }

    /**
     * Recalculate pivot levels daily
     */
    @Scheduled(cron = "0 15 9 * * *")  // 9:15 AM IST
    public void recalculatePivots() {
        for (String symbol : getActiveSymbols()) {
            try {
                // Get previous day candle
                UnifiedCandle prevDay = candleService.getCandle(symbol, Timeframe.D1,
                    LocalDate.now().minusDays(1).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant());

                if (prevDay == null) continue;

                // Calculate standard pivot points
                PivotLevels daily = calculatePivotLevels(prevDay);

                // Get previous week candle for weekly pivots
                UnifiedCandle prevWeek = candleService.getCandle(symbol, Timeframe.W1,
                    LocalDate.now().minusWeeks(1).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant());
                PivotLevels weekly = prevWeek != null ? calculatePivotLevels(prevWeek) : null;

                // Update state
                PivotState state = loadState(symbol);
                state.setDailyLevels(daily);
                state.setWeeklyLevels(weekly);
                state.setLastCalculated(Instant.now());

                saveState(symbol, state);

                log.info("[PIVOT] {} Daily: P={} S1={} R1={}",
                    symbol, daily.getPivot(), daily.getS1(), daily.getR1());

            } catch (Exception e) {
                log.error("Pivot calculation failed for {}", symbol, e);
            }
        }
    }

    private PivotLevels calculatePivotLevels(UnifiedCandle candle) {
        double high = candle.getHigh();
        double low = candle.getLow();
        double close = candle.getClose();

        double pivot = (high + low + close) / 3;
        double r1 = 2 * pivot - low;
        double s1 = 2 * pivot - high;
        double r2 = pivot + (high - low);
        double s2 = pivot - (high - low);
        double r3 = high + 2 * (pivot - low);
        double s3 = low - 2 * (high - pivot);

        return PivotLevels.builder()
            .pivot(pivot)
            .r1(r1).r2(r2).r3(r3)
            .s1(s1).s2(s2).s3(s3)
            .build();
    }

    private PivotRetest checkPivotRetest(UnifiedCandle candle, PivotState state) {
        if (state.getDailyLevels() == null) return null;

        double close = candle.getClose();
        double low = candle.getLow();
        double high = candle.getHigh();
        PivotLevels levels = state.getDailyLevels();

        // Check distance to each level
        Map<String, Double> levelDistances = Map.of(
            "pivot", Math.abs(close - levels.getPivot()),
            "r1", Math.abs(close - levels.getR1()),
            "r2", Math.abs(close - levels.getR2()),
            "s1", Math.abs(close - levels.getS1()),
            "s2", Math.abs(close - levels.getS2())
        );

        // Find nearest level
        String nearestLevel = levelDistances.entrySet().stream()
            .min(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);

        double nearestDistance = levelDistances.get(nearestLevel);
        double levelValue = getLevelValue(levels, nearestLevel);

        // Check if within retest zone (0.2% of level value)
        double retestThreshold = levelValue * 0.002;

        if (nearestDistance <= retestThreshold) {
            // Determine if support or resistance retest
            boolean isSupport = nearestLevel.startsWith("s") ||
                (nearestLevel.equals("pivot") && close > levelValue);

            // Check for bounce
            boolean bounced = isSupport ?
                (low <= levelValue && close > levelValue) :  // Support bounce
                (high >= levelValue && close < levelValue);   // Resistance bounce

            return PivotRetest.builder()
                .level(nearestLevel)
                .levelValue(levelValue)
                .retestTime(candle.getTimestamp())
                .retestPrice(close)
                .isSupport(isSupport)
                .bounced(bounced)
                .bouncePercent(bounced ? Math.abs(close - levelValue) / levelValue * 100 : 0)
                .build();
        }

        return null;
    }
}
```

### 4.4 IPU (Institutional Participation & Urgency) Strategy

```java
@Service
@Slf4j
public class IPUStrategy {

    @Autowired
    private CandleService candleService;

    @Autowired
    private StrategyStateRepository stateRepository;

    @Autowired
    private RedisTemplate<String, Object> redis;

    private static final int IPU_HISTORY_SIZE = 10;  // Keep last 10 readings for momentum

    /**
     * Calculate IPU score every 5 minutes
     */
    @Scheduled(cron = "0 */5 * * * *")
    public void calculateIPU() {
        for (String symbol : getActiveSymbols()) {
            try {
                IPUResult result = calculate(symbol);

                if (result.getScore() >= 7.0 && result.getMomentum() > 0) {
                    emitHighIPUSignal(symbol, result);
                }

            } catch (Exception e) {
                log.error("IPU calculation failed for {}", symbol, e);
            }
        }
    }

    public IPUResult calculate(String symbol) {
        // Load state
        IPUState state = loadState(symbol);

        // Get family data (equity + future + options)
        FamilyView family = candleService.getFamily(symbol, Timeframe.M5);

        if (family == null || family.getPrimaryCandle() == null) {
            return IPUResult.empty(symbol);
        }

        UnifiedCandle primary = family.getPrimaryCandle();
        UnifiedCandle future = family.getFutureCandle();
        List<OptionView> options = family.getOptions();

        // Calculate component scores
        double volumeScore = calculateVolumeScore(primary);
        double priceActionScore = calculatePriceActionScore(primary);
        double institutionalProxy = calculateInstitutionalProxy(primary, future, options);
        double urgencyScore = calculateUrgencyScore(primary);

        // Combine into final score
        double ipuScore = (volumeScore * 0.25 +
                          priceActionScore * 0.25 +
                          institutionalProxy * 0.35 +
                          urgencyScore * 0.15);

        // Determine direction
        String direction = determineDirection(primary, future, options);

        // Calculate momentum (change from previous readings)
        double momentum = calculateMomentum(state.getHistory(), ipuScore);

        // Update state
        state.addReading(IPUReading.builder()
            .timestamp(Instant.now())
            .score(ipuScore)
            .direction(direction)
            .build());
        state.trimHistory(IPU_HISTORY_SIZE);
        saveState(symbol, state);

        return IPUResult.builder()
            .symbol(symbol)
            .score(ipuScore)
            .direction(direction)
            .momentum(momentum)
            .components(IPUComponents.builder()
                .volumeScore(volumeScore)
                .priceActionScore(priceActionScore)
                .institutionalProxy(institutionalProxy)
                .urgencyScore(urgencyScore)
                .build())
            .build();
    }

    private double calculateInstitutionalProxy(UnifiedCandle primary,
                                               UnifiedCandle future,
                                               List<OptionView> options) {
        double score = 0.0;
        int components = 0;

        // 1. VPIN (informed trading probability)
        if (primary.getVpin() != null) {
            score += primary.getVpin();  // 0-1 scale
            components++;
        }

        // 2. Large trade concentration
        if (primary.getLargeTradeCount() != null && primary.getVolume() > 0) {
            // Higher large trade % = more institutional
            double largeTradeRatio = primary.getLargeTradeCount() /
                (primary.getVolume() / 10000.0);  // Normalized
            score += Math.min(1.0, largeTradeRatio);
            components++;
        }

        // 3. OFI direction strength
        if (primary.getOfi() != null && primary.getAvgDepth() > 0) {
            double ofiNormalized = Math.abs(primary.getOfi()) / primary.getAvgDepth();
            score += Math.min(1.0, ofiNormalized);
            components++;
        }

        // 4. Options flow (if available)
        if (options != null && !options.isEmpty()) {
            double optionFlowScore = calculateOptionFlowScore(options);
            score += optionFlowScore;
            components++;
        }

        // 5. Futures OI buildup (if available)
        if (future != null && future.getOiChange() != null) {
            double oiBuildupScore = calculateOIBuildupScore(future);
            score += oiBuildupScore;
            components++;
        }

        return components > 0 ? score / components : 0.5;
    }

    private double calculateMomentum(List<IPUReading> history, double currentScore) {
        if (history == null || history.size() < 3) {
            return 0.0;
        }

        // Simple momentum: current vs average of last 3
        double avgRecent = history.stream()
            .limit(3)
            .mapToDouble(IPUReading::getScore)
            .average()
            .orElse(currentScore);

        return currentScore - avgRecent;
    }
}
```

---

## 5. STRATEGY LAYER DESIGN

### 5.1 Complete Strategy Processing Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    STRATEGY EXECUTION FLOW                                   │
│                                                                              │
│   Every 1 minute:                                                           │
│   ┌──────────────────────────────────────────────────────────────────┐      │
│   │  1. Tick Aggregator emits 1m candle to MongoDB + Redis            │      │
│   │  2. Orderbook Aggregator emits 1m metrics to MongoDB + Redis      │      │
│   │  3. OI Aggregator emits 1m metrics to MongoDB + Redis             │      │
│   └──────────────────────────────────────────────────────────────────┘      │
│                              │                                               │
│                              ▼                                               │
│   Every 5 minutes:                                                          │
│   ┌──────────────────────────────────────────────────────────────────┐      │
│   │  4. TimeframeCacheWarmer aggregates 5m, 15m, 30m, 1h candles     │      │
│   │  5. VCP Strategy runs (needs 50 candles history)                  │      │
│   │  6. IPU Strategy runs (needs family view)                         │      │
│   │  7. Quant Score Calculator runs (aggregates all signals)          │      │
│   └──────────────────────────────────────────────────────────────────┘      │
│                              │                                               │
│                              ▼                                               │
│   Every 1 minute (real-time):                                               │
│   ┌──────────────────────────────────────────────────────────────────┐      │
│   │  8. Pivot Retest Strategy checks current price vs levels          │      │
│   │  9. Reversal Detection checks OFI velocity                        │      │
│   │  10. FUDKII checks latest candle against 50-candle history        │      │
│   └──────────────────────────────────────────────────────────────────┘      │
│                              │                                               │
│                              ▼                                               │
│   When signal triggered:                                                    │
│   ┌──────────────────────────────────────────────────────────────────┐      │
│   │  11. Signal Enrichment adds context (trend, levels, etc.)         │      │
│   │  12. Gate Chain filters low-quality signals                       │      │
│   │  13. Final signal published to trading-signals-v2 topic           │      │
│   └──────────────────────────────────────────────────────────────────┘      │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.2 Strategy Registry

```java
@Service
public class StrategyOrchestrator {

    @Autowired
    private List<Strategy> strategies;

    @Autowired
    private SignalPublisher signalPublisher;

    @Autowired
    private GateChain gateChain;

    /**
     * Process all strategies for a symbol
     */
    public List<Signal> processSymbol(String symbol) {
        List<Signal> signals = new ArrayList<>();

        for (Strategy strategy : strategies) {
            if (strategy.shouldRun(Instant.now())) {
                try {
                    StrategyResult result = strategy.execute(symbol);
                    if (result.hasSignal()) {
                        signals.add(result.getSignal());
                    }
                } catch (Exception e) {
                    log.error("Strategy {} failed for {}", strategy.getName(), symbol, e);
                }
            }
        }

        // Apply gate chain
        List<Signal> filteredSignals = gateChain.filter(signals);

        // Publish
        filteredSignals.forEach(signalPublisher::publish);

        return filteredSignals;
    }
}

// Strategy Interface
public interface Strategy {
    String getName();
    boolean shouldRun(Instant now);
    StrategyResult execute(String symbol);
    Timeframe getRequiredTimeframe();
    int getRequiredHistorySize();
}
```

---

## 6. REDIS CACHING STRATEGY

### 6.1 Redis Key Structure

```
# Candle Data (Hot)
tick:{symbol}:1m:latest              → UnifiedCandle (TTL: 5min)
tick:{symbol}:1m:history             → List<UnifiedCandle> (TTL: 24h, max 500)
tick:{symbol}:5m:latest              → UnifiedCandle (TTL: 5min)
tick:{symbol}:5m:history             → List<UnifiedCandle> (TTL: 24h, max 500)
... (for each popular timeframe)

# Orderbook Data
ob:{symbol}:latest                   → OrderbookMetrics (TTL: 5min)

# OI Data
oi:{symbol}:latest                   → OIMetrics (TTL: 5min)

# Strategy State
vcp:{symbol}:state                   → VCPState (TTL: 24h)
ipu:{symbol}:state                   → IPUState (TTL: 24h)
pivot:{symbol}:state                 → PivotState (TTL: 24h)
reversal:{symbol}:state              → ReversalState (TTL: 24h)
fudkii:{symbol}:state                → FUDKIIState (TTL: 24h)

# Computed Views
family:{symbol}:view                 → FamilyView (TTL: 1min)
trend:{symbol}:context               → TrendContext (TTL: 5min)

# Strategy Results (for dashboard)
signal:{symbol}:latest               → List<Signal> (TTL: 1h, last 10)
```

### 6.2 Redis Configuration

```java
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // Use JSON serialization for readability
        Jackson2JsonRedisSerializer<Object> serializer =
            new Jackson2JsonRedisSerializer<>(Object.class);

        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);

        return template;
    }

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory factory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(5))
            .serializeValuesWith(SerializationPair.fromSerializer(
                new GenericJackson2JsonRedisSerializer()));

        return RedisCacheManager.builder(factory)
            .cacheDefaults(config)
            .withCacheConfiguration("candles",
                config.entryTtl(Duration.ofMinutes(5)))
            .withCacheConfiguration("strategies",
                config.entryTtl(Duration.ofHours(24)))
            .build();
    }
}
```

---

## 7. IMPLEMENTATION PHASES

### Phase 1: Foundation (Week 1-2)

**Goal:** Create 3 independent aggregators, new data models, storage layer

| Task | Priority | Effort | Dependencies |
|------|----------|--------|--------------|
| Create TickCandle model (30 fields) | P0 | 2h | None |
| Create OrderbookMetrics model (20 fields) | P0 | 2h | None |
| Create OIMetrics model (10 fields) | P0 | 2h | None |
| Create MongoDB collections & indexes | P0 | 4h | Models |
| Create TickAggregator (Kafka → MongoDB + Redis) | P0 | 8h | Models, MongoDB |
| Create OrderbookAggregator | P0 | 6h | Models, MongoDB |
| Create OIAggregator | P0 | 4h | Models, MongoDB |
| Create CandleService (query layer) | P0 | 8h | Aggregators |
| Unit tests for aggregators | P1 | 8h | Aggregators |
| Integration tests | P1 | 8h | All above |

**Deliverables:**
- 3 independent Kafka consumers writing to MongoDB + Redis
- New data models (TickCandle, OrderbookMetrics, OIMetrics)
- CandleService for querying data

---

### Phase 2: Timeframe Aggregation (Week 2-3)

**Goal:** On-demand timeframe aggregation, cache warming

| Task | Priority | Effort | Dependencies |
|------|----------|--------|--------------|
| Create TimeframeAggregationService | P0 | 8h | CandleService |
| Create MongoDB aggregation pipelines | P0 | 6h | TimeframeService |
| Create TimeframeCacheWarmer | P0 | 6h | TimeframeService |
| Create TrendAnalysisService | P1 | 6h | TimeframeService |
| Performance optimization | P1 | 8h | All above |
| Tests for aggregation correctness | P0 | 8h | All above |

**Deliverables:**
- Timeframes 2m-1d available on-demand
- Popular timeframes pre-computed in Redis
- TrendContext available for strategies

---

### Phase 3: Strategy Migration (Week 3-4)

**Goal:** Convert VCP, IPU, Pivot to new architecture

| Task | Priority | Effort | Dependencies |
|------|----------|--------|--------------|
| Create StrategyState MongoDB collection | P0 | 4h | None |
| Create VCPStrategy (stateful) | P0 | 12h | CandleService, State |
| Create IPUStrategy (stateful) | P0 | 10h | CandleService, State |
| Create PivotRetestStrategy (stateful) | P0 | 10h | CandleService, State |
| Create ReversalStrategy (OFI velocity) | P1 | 8h | CandleService, State |
| Create StrategyOrchestrator | P0 | 6h | All strategies |
| Tests for strategy accuracy | P0 | 12h | All strategies |

**Deliverables:**
- VCP, IPU, Pivot strategies running on new architecture
- Strategy state persisted in MongoDB
- StrategyOrchestrator managing execution

---

### Phase 4: Family & Options (Week 4-5)

**Goal:** Family view, options flow, Greeks

| Task | Priority | Effort | Dependencies |
|------|----------|--------|--------------|
| Create FamilyService (equity + future + options) | P0 | 10h | CandleService |
| Create OptionsFlowService | P0 | 8h | FamilyService |
| Create GreeksService | P1 | 6h | OptionsFlowService |
| Integrate with IPU (institutional proxy) | P0 | 6h | FamilyService |
| Integrate with VCP (options confirmation) | P1 | 4h | FamilyService |
| Tests for family aggregation | P0 | 8h | All above |

**Deliverables:**
- FamilyView available on-demand
- Options flow integrated into strategies
- Greeks calculated for options

---

### Phase 5: Signal Layer & Cleanup (Week 5-6)

**Goal:** Signal enrichment, gate chain, delete old code

| Task | Priority | Effort | Dependencies |
|------|----------|--------|--------------|
| Migrate SignalEnrichment to new architecture | P0 | 8h | All strategies |
| Migrate GateChain to new architecture | P0 | 6h | SignalEnrichment |
| Create SignalPublisher (new) | P0 | 4h | GateChain |
| Delete UnifiedInstrumentCandleProcessor | P0 | 2h | All validated |
| Delete FamilyCandleProcessor | P0 | 2h | All validated |
| Delete TimeframeAggregator | P0 | 2h | All validated |
| Delete old models | P0 | 4h | All validated |
| Delete old Kafka topics | P1 | 2h | All validated |
| Final integration tests | P0 | 12h | All above |
| Documentation | P1 | 8h | All above |

**Deliverables:**
- Complete new architecture running
- Old code deleted (~8000 lines removed)
- 20+ Kafka topics deleted
- 15+ state stores deleted

---

## SUMMARY

### What We're Building

| Component | Old | New |
|-----------|-----|-----|
| Aggregators | 1 monolith (UnifiedInstrumentCandleProcessor) | 3 independent (Tick, OB, OI) |
| Data Joins | Kafka Streams JOIN | Query-time merge |
| State Stores | 15+ Kafka state stores | MongoDB + Redis |
| Timeframes | 10 Kafka topics | On-demand aggregation |
| Strategy State | In-memory/scattered | MongoDB (persistent) |
| Caching | Fragmented | Unified Redis strategy |

### Benefits

1. **Simpler Code:** Each component does one thing
2. **No Join Failures:** Joins at query time with explicit control
3. **Persistent State:** Strategy state survives restarts
4. **Flexible Timeframes:** Any timeframe on demand
5. **Better Debugging:** Clear data lineage
6. **Replay Support:** Query MongoDB for any historical window
7. **Lower Latency:** Redis for hot data, no stream-time delays

### Timeline

| Week | Deliverable |
|------|-------------|
| 1-2 | Independent aggregators + CandleService |
| 2-3 | Timeframe aggregation + TrendContext |
| 3-4 | VCP, IPU, Pivot strategies migrated |
| 4-5 | Family view + Options integration |
| 5-6 | Signal layer + Cleanup |

**Total: 6 weeks to complete refactoring**

---

*Plan created by Claude (Opus 4.5) - 2026-01-31*
