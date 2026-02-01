# Services Module

> **Package**: `com.kotsin.consumer.service`

The services layer provides **business logic** and **data access**.

---

## Overview

| Service | Purpose |
|---------|---------|
| `CandleService` | Query layer - merge data sources |
| `StrategyStateService` | Manage VCP/IPU/Pivot state |
| `TimeframeAggregationService` | On-demand aggregation |
| `RedisCacheService` | Hot cache operations |
| `TradingHoursValidationService` | Market hours validation |

---

## CandleService

**File**: `CandleService.java` (681 lines)

The **main interface** for strategies to fetch candle data.

### Key Responsibilities

1. **Query-time merge**: Combine TickCandle + OrderbookMetrics + OIMetrics
2. **Timeframe aggregation**: Compute higher timeframes on-demand
3. **Cache-first**: Redis for hot data, MongoDB fallback
4. **OI interpretation**: Calculate with price change

### API

```java
// Get latest 1m candle (merged)
UnifiedCandle getLatestCandle(String symbol);

// Get latest at specific timeframe (aggregated)
UnifiedCandle getLatestCandle(String symbol, Timeframe timeframe);

// Get candle at specific timestamp
UnifiedCandle getCandle(String symbol, Instant timestamp, Timeframe timeframe);

// Get history
List<UnifiedCandle> getCandleHistory(String symbol, Timeframe timeframe, int count);

// Batch queries
Map<String, UnifiedCandle> getLatestCandles(List<String> symbols);
Map<String, UnifiedCandle> getLatestCandles(List<String> symbols, Timeframe timeframe);

// Availability checks
boolean hasData(String symbol);
Set<String> getAvailableSymbols();
```

### Merge Logic

```java
UnifiedCandle getLatest1mCandle(String symbol) {
    // 1. Get tick candle (required)
    TickCandle tick = tickCandleRepository.findLatestBySymbol(symbol);
    if (tick == null) return null;
    
    // 2. Create unified candle from tick
    UnifiedCandle candle = UnifiedCandle.fromTick(tick, Timeframe.M1);
    
    // 3. Try to add orderbook (optional)
    OrderbookMetrics ob = orderbookMetricsRepository.findBySymbolAndTimestamp(
        symbol, tick.getTimestamp());
    if (ob != null) {
        candle = candle.withOrderbook(ob);
    }
    
    // 4. Try to add OI for derivatives (optional)
    if (tick.isDerivative()) {
        OIMetrics oi = oiMetricsRepository.findBySymbolAndTimestamp(
            symbol, tick.getTimestamp());
        if (oi != null) {
            calculateOIInterpretation(oi, tick);
            candle = candle.withOI(oi);
        }
    }
    
    return candle;
}
```

### Aggregation Logic

```java
UnifiedCandle aggregateLatestToTimeframe(String symbol, Timeframe timeframe) {
    // Get 1m candles needed for this timeframe
    int candlesNeeded = timeframe.getMinutes();
    List<TickCandle> ticks = tickCandleRepository.findLatestN(symbol, candlesNeeded);
    
    // Aggregate
    return aggregateTickCandles(ticks, timeframe);
}

UnifiedCandle aggregateTickCandles(List<TickCandle> ticks, Timeframe tf) {
    // OHLC: first open, max high, min low, last close
    double open = ticks.get(0).getOpen();
    double high = ticks.stream().mapToDouble(TickCandle::getHigh).max().orElse(0);
    double low = ticks.stream().mapToDouble(TickCandle::getLow).min().orElse(0);
    double close = ticks.get(ticks.size() - 1).getClose();
    
    // Volume: sum
    long volume = ticks.stream().mapToLong(TickCandle::getVolume).sum();
    
    // VWAP: weighted
    double vwap = ticks.stream()
        .mapToDouble(t -> t.getVwap() * t.getVolume())
        .sum() / volume;
    
    // ... more aggregation logic
}
```

---

## StrategyStateService

**File**: `StrategyStateService.java` (346 lines)

Manages per-symbol strategy state with caching.

### State Types

```java
public class StrategyState {
    // VCP State
    List<VolumeCluster> clusters;
    double poc, vah, val;
    
    // IPU State
    double ipuScore;
    double exhaustion;
    MomentumState momentum;
    
    // Pivot State
    List<PivotPoint> pivots;
    double nearestSupport;
    double nearestResistance;
}
```

### API

```java
// Get/update VCP state
VcpState getVcpState(String symbol, Timeframe tf);
void updateVcpState(String symbol, Timeframe tf, VcpState state);

// Get/update IPU state
IpuState getIpuState(String symbol);
void updateIpuState(String symbol, IpuState state);

// Get/update Pivot state
PivotState getPivotState(String symbol, Timeframe tf);
void updatePivotState(String symbol, Timeframe tf, PivotState state);

// Get combined state
StrategyState getState(String symbol);
```

### Caching

```java
// In-memory + Redis caching
@Cacheable(value = "vcpState", key = "#symbol + '-' + #timeframe")
public VcpState getVcpState(String symbol, Timeframe tf) {
    return strategyStateRepository.findBySymbolAndTimeframe(symbol, tf);
}
```

**Config**:
```properties
v2.strategy.state.cache.enabled=true
v2.strategy.state.cache.ttl.minutes=30
```

---

## TimeframeAggregationService

**File**: `TimeframeAggregationService.java` (278 lines)

Background service for pre-computing popular timeframes.

### Scheduled Aggregation

```java
@Scheduled(cron = "0 */5 * * * *")  // Every 5 minutes
public void aggregate5m() {
    aggregateAll(Timeframe.M5);
}

@Scheduled(cron = "0 */15 * * * *")  // Every 15 minutes
public void aggregate15m() {
    aggregateAll(Timeframe.M15);
}

// ... 30m, 1h, etc.
```

### API

```java
// Aggregate specific symbol
void aggregate(String symbol, Timeframe targetTimeframe);

// Aggregate all active symbols
void aggregateAll(Timeframe targetTimeframe);

// Get pre-computed aggregate
Optional<UnifiedCandle> getPrecomputed(String symbol, Timeframe tf);
```

---

## RedisCacheService

**File**: `RedisCacheService.java` (284 lines)

Redis operations for hot caching.

### Key Patterns

```
tick:latest:{symbol}         → Latest TickCandle
tick:history:{symbol}        → List of recent TickCandles
orderbook:latest:{symbol}    → Latest OrderbookMetrics
oi:latest:{symbol}           → Latest OIMetrics
state:vcp:{symbol}:{tf}      → VCP state
state:ipu:{symbol}           → IPU state
```

### API

```java
// Cache latest candle
void cacheLatest(TickCandle candle);
Optional<TickCandle> getLatest(String symbol);

// Manage history list
void appendToHistory(TickCandle candle);
List<TickCandle> getHistory(String symbol, int count);

// Cache strategy state
void cacheVcpState(String symbol, Timeframe tf, VcpState state);
Optional<VcpState> getVcpState(String symbol, Timeframe tf);
```

**Config**:
```properties
v2.cache.tick.latest.ttl.minutes=5
v2.cache.tick.history.ttl.hours=24
v2.cache.tick.history.max.size=500
```

---

## TradingHoursValidationService

**File**: `TradingHoursValidationService.java` (52 lines)

Validates if current time is within trading hours.

### API

```java
// Check if market is open
boolean isMarketOpen();
boolean isMarketOpen(String exchange);  // N, B, M

// Get current session
SessionType getCurrentSession();
// PREOPEN, OPENING, MORNING, MIDDAY, AFTERNOON, CLOSING, POSTMARKET

// Check specific constraints
boolean isWithinTradingWindow();
boolean isNearMarketOpen();   // First 15 minutes
boolean isNearMarketClose();  // Last 30 minutes
```

### Market Hours

| Exchange | Open | Close |
|----------|------|-------|
| NSE (N) | 9:15 | 15:30 |
| BSE (B) | 9:15 | 15:30 |
| MCX (M) | 9:00 | 23:30 |

**Config**:
```properties
session.market.open=09:15
session.market.close=15:30
session.or.15=15
session.or.30=30
session.or.60=60
```

---

## Repository Layer

### TickCandleRepository

**File**: `repository/TickCandleRepository.java`

```java
public interface TickCandleRepository extends MongoRepository<TickCandle, String> {
    
    Optional<TickCandle> findFirstBySymbolOrderByTimestampDesc(String symbol);
    
    List<TickCandle> findBySymbolAndTimestampBetween(
        String symbol, Instant start, Instant end);
    
    @Query(value = "{'symbol': ?0}", sort = "{'timestamp': -1}")
    List<TickCandle> findLatestN(String symbol, int limit);
}
```

### OrderbookMetricsRepository

```java
public interface OrderbookMetricsRepository extends MongoRepository<OrderbookMetrics, String> {
    
    Optional<OrderbookMetrics> findBySymbolAndTimestamp(String symbol, Instant timestamp);
    
    Optional<OrderbookMetrics> findFirstBySymbolOrderByTimestampDesc(String symbol);
}
```

### OIMetricsRepository

```java
public interface OIMetricsRepository extends MongoRepository<OIMetrics, String> {
    
    Optional<OIMetrics> findBySymbolAndTimestamp(String symbol, Instant timestamp);
    
    List<OIMetrics> findBySymbolAndTimestampAfter(String symbol, Instant since);
}
```

---

## Caching Strategy

```
┌───────────────────────────────────────────────────────────────┐
│                      CACHE HIERARCHY                          │
├───────────────────────────────────────────────────────────────┤
│                                                               │
│  Request ─▶ [Redis] ─miss─▶ [MongoDB] ─▶ Cache in Redis      │
│                │                                              │
│               hit                                             │
│                ▼                                              │
│            Response                                           │
│                                                               │
│  TTL:                                                         │
│  - Latest candle: 5 minutes                                   │
│  - History list: 24 hours                                     │
│  - Strategy state: 30 minutes                                 │
│  - MongoDB TTL: 90 days                                       │
│                                                               │
└───────────────────────────────────────────────────────────────┘
```
