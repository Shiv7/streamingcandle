# Data Models Reference

> **Package**: `com.kotsin.consumer.model`

This document describes all data models used in StreamingCandle.

---

## Input Models

### TickData

Raw tick from 5paisa feed via Kafka.

**Source**: `TickData.java` (177 lines)

```java
@Data
public class TickData {
    String exchange;       // "N" (NSE), "B" (BSE), "M" (MCX)
    String exchangeType;   // "C" (Cash), "D" (Derivative)
    int token;             // Exchange token
    String scripCode;      // Unique instrument ID
    double lastRate;       // Last traded price
    int lastQuantity;      // Last traded quantity
    int totalQuantity;     // Cumulative volume
    double high, low;      // Day high/low
    double openRate;       // Day open
    double previousClose;  // Previous day close
    double averageRate;    // VWAP
    long time;             // Unix timestamp
    int bidQuantity;       // Best bid qty
    double bidRate;        // Best bid price
    int offerQuantity;     // Best ask qty
    double offerRate;      // Best ask price
    String tickDt;         // Timestamp in /Date(...)/ format
    String companyName;    // Company/instrument name
}
```

### OrderBookSnapshot

Orderbook snapshot from Kafka.

**Source**: `OrderBookSnapshot.java`

```java
@Data
public class OrderBookSnapshot {
    String scripCode;
    double bestBid;
    double bestAsk;
    List<Level> bidLevels;  // Up to 5 levels
    List<Level> askLevels;
    long timestamp;
}
```

### OpenInterest

OI update from Kafka.

**Source**: `OpenInterest.java`

```java
@Data
public class OpenInterest {
    String scripCode;
    long openInterest;
    long previousDayOI;
    long timestamp;
}
```

---

## Stored Models

### TickCandle

Aggregated 1-minute candle stored in MongoDB.

**Source**: `TickCandle.java` (217 lines)  
**Collection**: `tick_candles_1m`

```java
@Document(collection = "tick_candles_1m")
public class TickCandle {
    // Identity
    String id;
    String symbol;           // "NIFTY", "RELIANCE"
    String scripCode;        // Exchange code
    String exchange;         // "N", "B", "M"
    String exchangeType;     // "C", "D"
    String companyName;
    InstrumentType instrumentType;  // INDEX, EQUITY, FUTURE, OPTION_CE, OPTION_PE
    
    // Timing
    Instant timestamp;       // Window end (canonical)
    Instant windowStart;
    Instant windowEnd;
    
    // OHLCV
    double open, high, low, close;
    long volume;
    double value;            // Turnover
    double vwap;
    
    // Trade Classification
    long buyVolume;          // Aggressive buys (lifted offers)
    long sellVolume;         // Aggressive sells (hit bids)
    long volumeDelta;        // buyVolume - sellVolume
    double buyPressure;      // buyVolume / total
    double sellPressure;     // sellVolume / total
    
    // VPIN
    double vpin;             // 0-1 probability of informed trading
    
    // Volume Profile
    double poc;              // Point of Control
    double vah;              // Value Area High
    double val;              // Value Area Low
    Map<Double, Long> volumeAtPrice;  // Sparse price→volume map
    
    // Imbalance Bars
    double volumeImbalance;
    boolean vibTriggered;
    boolean dibTriggered;
    boolean trbTriggered;
    
    // Tick Stats
    int tickCount;
    int largeTradeCount;     // Trades > 10x average
    
    // Quality
    String quality;          // VALID, WARNING, STALE
    long processingLatencyMs;
    
    // Options Metadata (if applicable)
    Double strikePrice;
    String optionType;       // "CE" or "PE"
    String expiry;           // "2026-01-30"
    Integer daysToExpiry;
}
```

**Indexes**:
- `symbol + timestamp` (compound, descending)
- `scripCode + timestamp` (compound, descending)
- `timestamp` (TTL: 90 days)

### OrderbookMetrics

Aggregated orderbook metrics.

**Source**: `OrderbookMetrics.java`  
**Collection**: `orderbook_metrics_1m`

```java
@Document(collection = "orderbook_metrics_1m")
public class OrderbookMetrics {
    String id;
    String symbol;
    String scripCode;
    Instant timestamp;
    Instant windowStart, windowEnd;
    
    // OFI
    double ofi;              // Cumulative Order Flow Imbalance
    double ofiMomentum;      // Rate of change
    
    // Kyle's Lambda
    double kyleLambda;       // Price impact coefficient
    
    // Microprice
    double microprice;       // Volume-weighted mid
    
    // Spread
    double bidAskSpread;     // Absolute spread
    double spreadPercent;    // Relative spread
    double spreadVolatility; // Spread variability
    
    // Depth
    double depthImbalance;   // (bidDepth - askDepth) / total
    double avgBidDepth;
    double avgAskDepth;
    double totalBidDepth;
    double totalAskDepth;
    
    // Anomaly Detection
    int spoofingCount;       // Suspicious cancellations
    boolean icebergBidDetected;
    boolean icebergAskDetected;
    
    // Stats
    int updateCount;
    long staleness;
}
```

### OIMetrics

Aggregated Open Interest metrics.

**Source**: `OIMetrics.java`  
**Collection**: `oi_metrics_1m`

```java
@Document(collection = "oi_metrics_1m")
public class OIMetrics {
    String id;
    String symbol;
    String scripCode;
    Instant timestamp;
    
    // OI OHLC
    long oiOpen, oiHigh, oiLow, oiClose;
    
    // Changes
    long oiChange;           // Current - previous
    double oiChangePercent;
    long oiFromPrevDay;      // Vs previous day
    
    // Velocity
    double oiVelocity;       // Rate of change
    
    // Interpretation
    OIInterpretation interpretation;  // LONG_BUILDUP, SHORT_COVERING, etc.
    double interpretationConfidence;
    boolean suggestsReversal;
    
    // Options (if applicable)
    String underlying;
    Double strikePrice;
    String optionType;
    
    long staleness;
    
    enum OIInterpretation {
        LONG_BUILDUP,
        SHORT_COVERING,
        SHORT_BUILDUP,
        LONG_UNWINDING,
        NEUTRAL
    }
}
```

---

## Query-Time Models

### UnifiedCandle

Merged view of TickCandle + OrderbookMetrics + OIMetrics.

**Source**: `UnifiedCandle.java` (337 lines)

This is **NOT stored** - it's computed at query time by `CandleService`.

```java
@Data
public class UnifiedCandle {
    // Identity
    String symbol, scripCode, exchange;
    InstrumentType instrumentType;
    Timeframe timeframe;
    
    // Timing
    Instant timestamp, windowStart, windowEnd;
    
    // === FROM TICK (Always present) ===
    double open, high, low, close;
    long volume;
    double vwap;
    long buyVolume, sellVolume, volumeDelta;
    double buyPressure, sellPressure;
    double vpin;
    double poc, vah, val;
    boolean vibTriggered, dibTriggered;
    int tickCount, largeTradeCount;
    
    // === FROM ORDERBOOK (Optional) ===
    boolean hasOrderbook;      // ← Availability flag
    Double ofi, ofiMomentum;
    Double kyleLambda;
    Double microprice;
    Double bidAskSpread, spreadPercent;
    Double depthImbalance, avgBidDepth, avgAskDepth;
    Integer spoofingCount;
    Boolean icebergDetected;
    
    // === FROM OI (Optional, derivatives only) ===
    boolean hasOI;             // ← Availability flag
    Long openInterest, oiChange;
    Double oiChangePercent;
    OIInterpretation oiInterpretation;
    Double oiInterpretationConfidence;
    Boolean oiSuggestsReversal;
    Double oiVelocity;
    
    // Options Greeks (computed on-demand)
    Double delta, gamma, theta, vega, impliedVolatility;
    
    // Quality
    String quality;
    long tickStaleness;
    Long orderbookStaleness, oiStaleness;
    
    // Aggregation Info (for higher timeframes)
    int aggregatedCandleCount;
    int expectedCandleCount;
    double completenessRatio;
    
    // Factory methods
    static UnifiedCandle fromTick(TickCandle tick, Timeframe tf);
    UnifiedCandle withOrderbook(OrderbookMetrics ob);
    UnifiedCandle withOI(OIMetrics oi);
    
    // Helpers
    boolean isDerivative();
    boolean isOption();
    boolean isBullish();
    boolean isAllDataFresh();
}
```

---

## Timeframe Enum

**Source**: `Timeframe.java` (106 lines)

```java
public enum Timeframe {
    M1("1m", 1),
    M2("2m", 2),
    M3("3m", 3),
    M5("5m", 5),
    M15("15m", 15),
    M30("30m", 30),
    H1("1h", 60),
    H2("2h", 120),
    H4("4h", 240),
    D1("1d", 1440),
    W1("1w", 10080);
    
    String getLabel();
    int getMinutes();
    Duration getDuration();
    Instant alignToWindowStart(Instant timestamp);
    Instant getWindowEnd(Instant windowStart);
    boolean isPopular();  // M5, M15, M30, H1, H4, D1
    
    static Timeframe fromLabel(String label);
}
```

---

## InstrumentType Enum

```java
public enum InstrumentType {
    INDEX,      // NIFTY, BANKNIFTY
    EQUITY,     // RELIANCE, TCS
    FUTURE,     // NIFTY-FUT
    OPTION_CE,  // Call option
    OPTION_PE;  // Put option
    
    boolean isOption();
    boolean isDerivative();
    
    static InstrumentType detect(String exchange, String exchangeType, String companyName);
}
```

Detection logic:
- Contains " CE " → `OPTION_CE`
- Contains " PE " → `OPTION_PE`
- Contains "FUT" or exchangeType = "D" → `FUTURE`
- Contains "NIFTY" (no space) → `INDEX`
- Default → `EQUITY`
