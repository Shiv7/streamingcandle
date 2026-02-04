# Aggregators Module

> **Package**: `com.kotsin.consumer.aggregator`

The aggregator layer is responsible for **ingesting raw Kafka data** and producing 1-minute aggregated records.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    INDEPENDENT CONSUMERS                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────┐    ┌──────────────────┐    ┌─────────────┐    │
│  │ Kafka Topic │───▶│   Aggregator     │───▶│ MongoDB +   │    │
│  │             │    │   (wall-clock)   │    │ Redis       │    │
│  └─────────────┘    └──────────────────┘    └─────────────┘    │
│                                                                  │
│  forwardtesting-data ──▶ TickAggregator ──▶ tick_candles_1m    │
│  Orderbook ──▶ OrderbookAggregator ──▶ orderbook_metrics_1m    │
│  OpenInterest ──▶ OIAggregator ──▶ oi_metrics_1m               │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## TickAggregator

**File**: `TickAggregator.java` (544 lines)

### Purpose
Consumes tick data and aggregates into 1-minute `TickCandle` with:
- OHLCV (Open, High, Low, Close, Volume)
- Trade classification (buy/sell volume)
- Volume profile (POC, VAH, VAL)
- VPIN calculation
- Imbalance bar detection

### Key Methods

| Method | Purpose |
|--------|---------|
| `start()` | Initialize consumer, start processing loop |
| `consumeLoop()` | Poll Kafka, process ticks |
| `processTick()` | Update aggregate state for instrument |
| `checkWindowEmission()` | Check if 1-minute window complete |
| `emitCurrentWindow()` | Finalize and save all candles |

### Internal State Class

```java
class TickAggregateState {
    String symbol, scripCode;
    double open, high, low, close;
    long volume, buyVolume, sellVolume;
    double vpin;
    Map<Double, Long> volumeAtPrice;  // For POC/VAH/VAL
    // ...
    
    void update(TickData tick, Instant tickTime);
    TickCandle toTickCandle();
    void reset(Instant newWindowStart, Instant newWindowEnd);
}
```

### Configuration

```properties
v2.tick.aggregator.enabled=true
v2.tick.input.topic=forwardtesting-data
v2.tick.consumer.group=logging-test-tick-agg-20260131
v2.tick.aggregator.threads=4
```

---

## OrderbookAggregator

**File**: `OrderbookAggregator.java` (496 lines)

### Purpose
Consumes orderbook snapshots and produces 1-minute `OrderbookMetrics`:
- OFI (Order Flow Imbalance)
- Kyle's Lambda (price impact)
- Spread metrics (bid-ask spread, spread volatility)
- Depth metrics (bid/ask depth, imbalance)
- Anomaly detection (spoofing, iceberg)

### Key Calculations

**OFI (Order Flow Imbalance):**
```java
double bidChange = currentBidQty - previousBidQty;
double askChange = currentAskQty - previousAskQty;
ofi = bidChange - askChange;
cumulativeOfi += ofi;
```

**Kyle's Lambda:**
```java
// Regression: ΔPrice = λ × ΔOrderFlow
// Higher λ = less liquid market
lambda = sumPriceChangeOrderFlow / sumOrderFlowSquared;
```

### Configuration

```properties
v2.orderbook.aggregator.enabled=true
v2.orderbook.input.topic=Orderbook
v2.orderbook.consumer.group=logging-test-ob-agg-20260131
v2.orderbook.aggregator.threads=2
```

---

## OIAggregator

**File**: `OIAggregator.java` (483 lines)

### Purpose
Consumes Open Interest updates and produces 1-minute `OIMetrics`:
- OI OHLC (Open, High, Low, Close)
- OI change and change percentage
- OI velocity
- Interpretation (requires price from TickCandle)

### OI Interpretation Logic

```java
// Calculated later by CandleService with price change
if (oiChange > 0 && priceChange > 0) return LONG_BUILDUP;
if (oiChange < 0 && priceChange > 0) return SHORT_COVERING;
if (oiChange > 0 && priceChange < 0) return SHORT_BUILDUP;
if (oiChange < 0 && priceChange < 0) return LONG_UNWINDING;
return NEUTRAL;
```

### Instrument Parsing

The aggregator parses option details from company names:
```java
// "NIFTY 23JAN 21000 CE" → underlying=NIFTY, strike=21000, type=CE
// "BANKNIFTY 25JAN 48000 PE" → underlying=BANKNIFTY, strike=48000, type=PE
// "RELIANCE" → underlying=RELIANCE (equity/futures)
```

### Configuration

```properties
v2.oi.aggregator.enabled=true
v2.oi.input.topic=OpenInterest
v2.oi.consumer.group=logging-test-oi-agg-20260131
v2.oi.aggregator.threads=2
```

---

## Common Patterns

### Wall-Clock Based Emission

All aggregators emit on **wall-clock time**, not stream time:

```java
private void checkWindowEmission() {
    Instant now = Instant.now();
    if (now.isAfter(currentWindowEnd)) {
        emitCurrentWindow();
        advanceWindow();
    }
}
```

This ensures consistent 1-minute boundaries regardless of data arrival timing.

### Dual Storage (MongoDB + Redis)

Each aggregator writes to both storage systems:

```java
private void emitCurrentWindow() {
    for (AggregateState state : activeStates.values()) {
        Record record = state.toRecord();
        
        // MongoDB for persistence
        repository.save(record);
        
        // Redis for hot cache
        redisService.cacheLatest(record);
        redisService.appendToHistory(record);
    }
}
```

### Symbol Extraction

```java
private String extractSymbol(String companyName, String scripCode) {
    // Try to extract clean symbol from company name
    // "RELIANCE INDUSTRIES LTD" → "RELIANCE"
    // "NIFTY 23JAN 21000 CE" → "NIFTY"
    
    if (companyName == null) return scripCode;
    String[] parts = companyName.split(" ");
    return parts[0].toUpperCase();
}
```

---

## Stats & Monitoring

Each aggregator exposes stats via `getStats()`:

```java
Map<String, Object> stats = tickAggregator.getStats();
// Returns:
// - running: true/false
// - activeInstruments: count
// - ticksProcessed: count
// - candlesEmitted: count
// - currentWindow: timestamp
```

Access via `/api/health` or programmatically.
