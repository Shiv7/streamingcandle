# Streamingcandle - Real-time Market Data Aggregation

## Table of Contents
- [Overview](#overview)
- [Architecture](#architecture)
- [Data Flow](#data-flow)
- [Output Topics](#output-topics)
- [Technical Implementation](#technical-implementation)
- [Configuration](#configuration)
- [Running the Module](#running-the-module)
- [Development Guide](#development-guide)
- [Troubleshooting](#troubleshooting)

---

## Overview

**Streamingcandle** is a high-performance Kafka Streams application that aggregates real-time market data from Indian stock exchanges (NSE/MCX) into multi-timeframe candles with enriched market microstructure metrics.

### Business Problem

Traders need aggregated market data across multiple timeframes (1m, 2m, 3m, 5m, 15m, 30m) with:
- OHLCV candles from trade ticks
- Orderbook depth and microstructure signals
- Open Interest metrics (for derivatives)
- All synchronized and aligned to market hours (9:15 AM IST)

### Key Features

- **3 Independent Streams** - No merging/joining to avoid lateness issues
- **Market-Aligned Windows** - Windows start from market open (9:15 AM IST), not clock hours
- **6 Timeframes** - 1m, 2m, 3m, 5m, 15m, 30m
- **Enriched Data** - OHLCV + orderbook depth + microstructure + OI + volume profile
- **High Performance** - Per-instrument aggregation, optimized state stores
- **Fault Tolerant** - Kafka Streams guarantees, state store backups

---

## Architecture

### High-Level Design

```
┌────────────────────────────────────────────────────────────────┐
│                  INPUT TOPICS (3)                              │
├────────────────────────────────────────────────────────────────┤
│ 1. forwardtesting-data  →  Key: {token}        (Trade Ticks)  │
│ 2. Orderbook            →  Key: {exch}|{token} (Depth)        │
│ 3. OpenInterest         →  Key: {exch}|{token} (OI)           │
└────────────────────────────────────────────────────────────────┘
                              ↓
┌────────────────────────────────────────────────────────────────┐
│         3 INDEPENDENT KAFKA STREAMS TOPOLOGIES                 │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Stream 1: Ticks → OHLCV Candles                               │
│    • CumToDeltaTransformer (cumulative → delta volume)         │
│    • Market-aligned windowing (9:15 AM base)                   │
│    • Per-instrument state accumulation                         │
│    • CandleAccumulator, VolumeProfileAccumulator               │
│                                                                 │
│  Stream 2: Orderbook → Orderbook Signals                       │
│    • Key normalization (exch|token → token)                    │
│    • Market-aligned windowing                                  │
│    • OrderbookDepthAccumulator, MicrostructureAccumulator      │
│    • Iceberg/spoofing detection                                │
│                                                                 │
│  Stream 3: OpenInterest → OI Metrics                           │
│    • Key normalization (exch|token → token)                    │
│    • Market-aligned windowing                                  │
│    • OiAccumulator (OI OHLC tracking)                          │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
                              ↓
┌────────────────────────────────────────────────────────────────┐
│                  OUTPUT TOPICS (18)                             │
├────────────────────────────────────────────────────────────────┤
│ • candle-ohlcv-{1m,2m,3m,5m,15m,30m}         (6 topics)       │
│ • orderbook-signals-{1m,2m,3m,5m,15m,30m}    (6 topics)       │
│ • oi-metrics-{1m,2m,3m,5m,15m,30m}           (6 topics)       │
└────────────────────────────────────────────────────────────────┘
```

### Why 3 Independent Streams?

**Problem with Merging**: When different data sources (ticks, orderbook, OI) are merged, stream time advances based on the latest event. This causes earlier events to be marked as "late" and dropped.

**Solution**: Process each stream independently with its own event time, then let downstream consumers join data as needed.

---

## Data Flow

### Stream 1: Ticks → OHLCV Candles

**Input**: `forwardtesting-data` topic
```json
{
  "Token": 96955,
  "companyName": "INDUSINDBK 28 OCT 2025 PE 760.00",
  "Exch": "N",
  "ExchType": "D",
  "LastRate": 10.2,
  "LastQty": 1400,
  "TotalQty": 25200,
  "High": 11.6,
  "Low": 8.5,
  "OpenRate": 10.5,
  "AvgRate": 9.69,
  "BidRate": 10.15,
  "OffRate": 10.55,
  "TickDt": "/Date(1761034637000)/"
}
```

**Processing**:
1. Convert cumulative volume to delta volume (`CumToDeltaTransformer`)
2. Group by token (scripCode)
3. Window by timeframe (market-aligned)
4. Accumulate OHLCV (`CandleAccumulator`)
5. Calculate volume profile, imbalance bars
6. Emit completed window

**Output**: `candle-ohlcv-{timeframe}` topics
```json
{
  "scripCode": "96955",
  "companyName": "INDUSINDBK 28 OCT 2025 PE 760.00",
  "timeframe": "1m",
  "windowStartMillis": 1761034560000,
  "windowEndMillis": 1761034620000,
  "candle": {
    "open": 10.5,
    "high": 11.2,
    "low": 10.1,
    "close": 10.8,
    "volume": 12600,
    "buyVolume": 7200,
    "sellVolume": 5400,
    "vwap": 10.65,
    "tickCount": 42,
    "isComplete": true
  },
  "volumeProfile": {
    "poc": 10.6,
    "valueAreaHigh": 11.0,
    "valueAreaLow": 10.3
  }
}
```

### Stream 2: Orderbook → Orderbook Signals

**Input**: `Orderbook` topic
```json
{
  "Token": 92960,
  "companyName": "INFY 28 OCT 2025 PE 1460.00",
  "Exch": "N",
  "ExchType": "D",
  "receivedTimestamp": 1761034637914,
  "bids": [
    {"Price": 20.2, "Quantity": 1600, "NumberOfOrders": 4},
    {"Price": 20.15, "Quantity": 1600, "NumberOfOrders": 4}
  ],
  "asks": [
    {"Price": 20.4, "Quantity": 400, "NumberOfOrders": 1},
    {"Price": 20.5, "Quantity": 1600, "NumberOfOrders": 2}
  ],
  "TBidQ": 426400,
  "TOffQ": 109600
}
```

**Processing**:
1. Extract token from composite key (`N|92960` → `92960`)
2. Group by token
3. Window by timeframe (market-aligned)
4. Accumulate orderbook depth metrics
5. Calculate OFI, VPIN, microstructure indicators
6. Detect icebergs and spoofing
7. Emit completed window

**Output**: `orderbook-signals-{timeframe}` topics
```json
{
  "scripCode": "92960",
  "timeframe": "1m",
  "orderbookSignals": {
    "depthImbalance": 0.59,
    "depthBuyImbalanced": true,
    "ofi": 25600,
    "vpinLevel": "MODERATE",
    "spreadAvg": 0.2,
    "bidDepthSum": 426400,
    "askDepthSum": 109600,
    "icebergBid": true,
    "icebergAsk": false,
    "spoofingCount": 0
  }
}
```

### Stream 3: OI → OI Metrics

**Input**: `OpenInterest` topic
```json
{
  "Token": 126978,
  "companyName": "ITC 25 NOV 2025 CE 412.50",
  "Exch": "N",
  "ExchType": "D",
  "receivedTimestamp": 1761034638963,
  "OpenInterest": 59200
}
```

**Processing**:
1. Extract token from composite key
2. Group by token
3. Window by timeframe
4. Track OI changes (OHLC for OI)
5. Emit completed window

**Output**: `oi-metrics-{timeframe}` topics
```json
{
  "scripCode": "126978",
  "timeframe": "1m",
  "openInterest": {
    "oiClose": 59200,
    "oiChange": 1200,
    "oiChangePercent": 2.07
  }
}
```

---

## Output Topics

### Topic Naming Convention

All output topics follow the pattern: `{type}-{timeframe}`

| Stream Type | Timeframes | Topic Names |
|------------|-----------|-------------|
| OHLCV Candles | 1m, 2m, 3m, 5m, 15m, 30m | `candle-ohlcv-1m`, `candle-ohlcv-2m`, ... |
| Orderbook Signals | 1m, 2m, 3m, 5m, 15m, 30m | `orderbook-signals-1m`, `orderbook-signals-2m`, ... |
| OI Metrics | 1m, 2m, 3m, 5m, 15m, 30m | `oi-metrics-1m`, `oi-metrics-2m`, ... |

**Total**: 18 output topics (3 types × 6 timeframes)

### Message Format

All output topics use `UnifiedWindowMessage` wrapper:
- **Key**: `scripCode` (token as string)
- **Value**: JSON with sections for candle, orderbook, OI, volume profile

---

## Technical Implementation

### Key Components

#### 1. TopologyConfiguration
Creates 3 independent Kafka Streams topologies:
- `createTicksTopology()` - OHLCV candle aggregation
- `createOrderbookTopology()` - Orderbook signal generation
- `createOITopology()` - OI metrics aggregation

#### 2. InstrumentStateManager
Manages per-instrument state accumulation across all timeframes:
- `CandleAccumulator` - OHLCV calculation
- `MicrostructureAccumulator` - Orderbook microstructure
- `ImbalanceBarAccumulator` - Volume imbalance bars
- `OrderbookDepthAccumulator` - Depth metrics
- `VolumeProfileAccumulator` - POC and value area

#### 3. MarketAlignedTimestampExtractor
Aligns all windows to market open (9:15 AM IST):
```java
// Example: 9:17:35 → Align to 2-minute window [9:16:00 - 9:18:00]
long marketOpenMillis = 1761034500000L; // 9:15 AM IST
long elapsedSinceOpen = timestamp - marketOpenMillis;
long windowIndex = elapsedSinceOpen / windowDurationMillis;
return windowIndex * windowDurationMillis;
```

#### 4. CumToDeltaTransformer
Converts cumulative volume to delta volume:
- Tracks previous cumulative volume per instrument
- Calculates delta: `deltaVolume = currentVolume - previousVolume`
- Handles reset scenarios (next day, market restart)

### Windowing Strategy

**Market-Aligned Windows** (NOT clock-based):

```
NSE Open: 9:15:00 AM IST

1-minute windows:
  [9:15:00 - 9:16:00]
  [9:16:00 - 9:17:00]
  [9:17:00 - 9:18:00]
  ...

30-minute windows:
  [9:15:00 - 9:45:00]  ✅ Correct
  [9:45:00 - 10:15:00]
  [10:15:00 - 10:45:00]
  ...
```

**Grace Period**: 10 seconds (configurable via `unified.streams.window.grace.period.seconds`)

### State Stores

Each topology uses persistent state stores for windowing:
- `tick-state-{timeframe}` - Tick aggregation state
- `orderbook-depth-{timeframe}` - Orderbook accumulation state
- `oi-state-{timeframe}` - OI tracking state

State stores are backed up to changelog topics for fault tolerance.

---

## Configuration

### Core Configuration

```properties
# Application
spring.application.name=streamingcandle
server.port=8081

# Kafka Bootstrap
spring.kafka.bootstrap-servers=localhost:9092

# Kafka Streams
spring.kafka.streams.application-id=unified-market-processor
spring.kafka.streams.state-dir=/tmp/kafka-streams/streamingcandle

# Processing Guarantee
spring.kafka.streams.properties.processing.guarantee=at_least_once
spring.kafka.streams.properties.replication.factor=1

# Performance Tuning
spring.kafka.streams.properties.commit.interval.ms=100
spring.kafka.streams.properties.statestore.cache.max.bytes=104857600
spring.kafka.streams.properties.num.stream.threads=1
```

### Input Topics

```properties
# Input topic configuration
unified.input.topic.ticks=forwardtesting-data
unified.input.topic.oi=OpenInterest
unified.input.topic.orderbook=Orderbook
```

### Output Topics

```properties
# Enable/disable outputs
stream.outputs.candles.enabled=true
stream.outputs.familyStructured.enabled=false

# Output topic names (per timeframe)
stream.outputs.candles.1m=candle-ohlcv-1m
stream.outputs.candles.2m=candle-ohlcv-2m
stream.outputs.candles.3m=candle-ohlcv-3m
stream.outputs.candles.5m=candle-ohlcv-5m
stream.outputs.candles.15m=candle-ohlcv-15m
stream.outputs.candles.30m=candle-ohlcv-30m
```

### Market Hours

```properties
# Trading hours validation
trading.hours.validation.enabled=false

# Market open time (IST) - default 9:15 AM
market.open.hour=9
market.open.minute=15
```

### Windowing

```properties
# Grace period for late-arriving events (seconds)
unified.streams.window.grace.period.seconds=10
```

### Profiles

Three profiles available:
- **production** (default) - Production Kafka, latest offset
- **test** - Test environment configuration
- **local** - Local development with debug logging

Switch profiles:
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=test
```

---

## Running the Module

### Prerequisites

- Java 17+
- Maven 3.8+
- Kafka 3.0+ running
- Input topics created and populated:
  - `forwardtesting-data`
  - `Orderbook`
  - `OpenInterest`

### Build

```bash
cd streamingcandle
mvn clean package
```

### Run

**Production Mode**:
```bash
mvn spring-boot:run
```

**With Specific Profile**:
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=test
```

**From JAR**:
```bash
java -jar target/streamingcandle-1.0.0.jar
```

### Health Check

```bash
curl http://localhost:8081/health
```

### Metrics Endpoint

```bash
curl http://localhost:8081/metrics
```

---

## Development Guide

### Adding a New Timeframe

1. Add to `Timeframe` enum:
```java
// monitoring/Timeframe.java
FORTY_FIVE_MIN(45, "45m"),
```

2. Configure output topic:
```properties
# application.properties
stream.outputs.candles.45m=candle-ohlcv-45m
```

3. Update `TopologyConfiguration`:
```java
for (Timeframe tf : Timeframe.values()) {
    buildTickCandles(builder, processedTicks, tf);
}
```

### Adding New Metrics

1. Update accumulator (e.g., `CandleAccumulator.java`):
```java
private Double newMetric;

public void addTick(TickData tick) {
    // Calculate new metric
    newMetric = ...;
}
```

2. Update output model (`InstrumentCandle.java`):
```java
private Double newMetric;
```

3. Map in `InstrumentStateManager`:
```java
candle.setNewMetric(accumulator.getNewMetric());
```

### Testing

**Unit Tests**:
```bash
mvn test
```

**Integration Tests**:
```bash
mvn verify
```

**Manual Testing with Kafka**:
```bash
# Produce test message
kafka-console-producer --bootstrap-server localhost:9092 --topic forwardtesting-data

# Consume output
kafka-console-consumer --bootstrap-server localhost:9092 --topic candle-ohlcv-1m --from-beginning
```

---

## Troubleshooting

### Issue: No Output Messages

**Symptoms**: Kafka Streams app running, but no messages in output topics

**Possible Causes**:
1. **Window not closed yet** - Wait for window duration + grace period
2. **Trading hours validation** - Check if `trading.hours.validation.enabled=true` and market is closed
3. **Invalid input data** - Check logs for validation errors
4. **Offset at end** - Input topic offset at latest, no new messages

**Solutions**:
```properties
# Consume from beginning for testing
spring.kafka.streams.properties.auto.offset.reset=earliest

# Disable trading hours validation
trading.hours.validation.enabled=false
```

### Issue: High Memory Usage

**Symptoms**: Application using excessive memory

**Solutions**:
```properties
# Reduce state store cache
spring.kafka.streams.properties.statestore.cache.max.bytes=52428800

# Reduce commit interval (more frequent writes)
spring.kafka.streams.properties.commit.interval.ms=500
```

### Issue: Late Records Dropped

**Symptoms**: Logs show "Skipping record at timestamp X, window already closed"

**Solutions**:
```properties
# Increase grace period
unified.streams.window.grace.period.seconds=30
```

### Issue: Orderbook-Only Windows Return Null

**Symptoms**: Windows with only orderbook data (no ticks) are not emitted

**Status**: ✅ **FIXED** - Now handles orderbook-only windows correctly

**Verification**:
- Check `InstrumentStateManager.buildOrderbookOnlyCandle()`
- Ensure orderbook messages have valid token

### Common Log Messages

**Normal**:
```
📤 [1m] OHLCV emitted: scrip=96955 vol=12600
✅ Started TICKS consumer (candle-ohlcv-*)
```

**Warning**:
```
⚠️ Invalid tick received for timeframe 1m (missing scripCode)
⚠️ Trading hours validation: tick outside market hours
```

**Error**:
```
🚨 Kafka Streams exception: Deserialization error
🚨 State store restore failed, rebalancing
```

---

## Project Structure

```
streamingcandle/
├── src/main/java/com/kotsin/consumer/
│   ├── config/              # Kafka configuration
│   │   ├── KafkaConfig.java
│   │   └── ConfigurationValidator.java
│   ├── processor/           # Stream topologies and accumulators
│   │   ├── TopologyConfiguration.java (3 stream topologies)
│   │   ├── CandleAccumulator.java
│   │   ├── OrderbookDepthAccumulator.java
│   │   ├── MicrostructureAccumulator.java
│   │   ├── ImbalanceBarAccumulator.java
│   │   └── VolumeProfileAccumulator.java
│   ├── service/             # Business logic services
│   │   ├── InstrumentStateManager.java (main aggregation)
│   │   ├── IcebergDetectionService.java
│   │   ├── SpoofingDetectionService.java
│   │   └── TradingHoursValidationService.java
│   ├── transformers/        # Kafka Streams transformers
│   │   ├── CumToDeltaTransformer.java
│   │   └── OiDeltaTransformer.java
│   ├── time/                # Timestamp extraction
│   │   └── MarketAlignedTimestampExtractor.java
│   ├── model/               # Data models
│   │   ├── InstrumentCandle.java (output model)
│   │   ├── UnifiedWindowMessage.java (wrapper)
│   │   ├── TickData.java
│   │   ├── OrderBookSnapshot.java
│   │   └── OpenInterest.java
│   └── ConsumerApplication.java (main entry point)
├── src/main/resources/
│   ├── application.properties (production config)
│   ├── application-test.properties
│   └── application-local.properties
└── pom.xml
```

---

## License

[Add your license here]

## Contributing

[Add contribution guidelines here]

## Contact

For issues or questions, contact: [your contact info]

