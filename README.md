# StreamingCandle - Unified Family Candle Architecture

Real-time market data processing with cross-instrument analysis.

## Overview

StreamingCandle consumes raw market data (ticks, orderbook, OI) and produces unified Family Candles that combine:
- **Equity/Index** - OHLCV with microstructure metrics
- **Futures** - OI buildup signals (LONG_BUILDUP, SHORT_BUILDUP, etc.)
- **Options** - PCR, OI changes, IV metrics

## Architecture

```
Raw Data (3 Topics)          Processing                    Output (12+ Topics)
┌─────────────────┐    ┌───────────────────────┐    ┌─────────────────────┐
│ forwardtesting  │───►│ UnifiedInstrument     │───►│ instrument-candle-1m│
│ -data (Ticks)   │    │ CandleProcessor       │    └──────────┬──────────┘
├─────────────────┤    │ (LEFT JOIN)           │               │
│ Orderbook       │───►│                       │               ▼
├─────────────────┤    └───────────────────────┘    ┌─────────────────────┐
│ OpenInterest    │───►                             │ FamilyCandle        │
└─────────────────┘                                 │ Processor           │
                                                    └──────────┬──────────┘
                                                               │
                                                               ▼
                                              ┌────────────────────────────┐
                                              │ TimeframeAggregator        │
                                              ├────────────────────────────┤
                                              │ family-candle-1m  ─────────┼─► 1m
                                              │ family-candle-2m  ─────────┼─► 2m
                                              │ family-candle-5m  ─────────┼─► 5m
                                              │ family-candle-15m ─────────┼─► 15m
                                              │ family-candle-1h  ─────────┼─► 1h
                                              │ family-candle-1d  ─────────┼─► Daily
                                              └────────────────────────────┘
```

## Key Features

### 1. Unified Instrument Candle
Combines tick, orderbook, and OI data with **LEFT JOIN semantics**:
- Tick data is **mandatory**
- Orderbook and OI are **optional enrichments**
- Never discards candles - uses `hasOrderbook` and `hasOI` flags

### 2. Family Candle
Groups related instruments for cross-instrument analysis:
- Equity + Future + 4 ATM options
- Calculates PCR, spot-future premium, OI signals
- Detects buildup patterns (BULLISH_ACCUMULATION, BEARISH_DISTRIBUTION)

### 3. Adaptive VPIN
Fixes the hardcoded bucket size bug:
```java
// OLD: VPIN_INITIAL_BUCKET_SIZE = 10000.0  (one size fits all)
// NEW: bucketSize = avgDailyVolume / 50    (adaptive per instrument)
```

### 4. Cross-Instrument OI Signals
```
BULLISH_ACCUMULATION  - Price ↑ + Future OI ↑ + Call OI ↑
BEARISH_DISTRIBUTION  - Price ↓ + Future OI ↑ + Put OI ↑
SHORT_COVERING_RALLY  - Price ↑ + Future OI ↓ + Put OI ↓
LONG_UNWINDING        - Price ↓ + Future OI ↓ + Call OI ↓
```

## Data Availability Pattern

| Instrument | OHLCV | Orderbook | OI |
|------------|-------|-----------|-----|
| Index      | ✅    | ❌        | ❌  |
| Equity     | ✅    | ✅        | ❌  |
| Derivatives| ✅    | ✅        | ✅  |

## Configuration

Key settings in `application.properties`:

```properties
# Enable/disable processors
unified.processor.enabled=true
timeframe.aggregator.enabled=true

# ScripFinder API for family mappings
scripfinder.api.base-url=http://13.203.60.173:8102
scripfinder.api.timeout.ms=3000

# Family cache
family.cache.ttl.hours=24
```

## New Domain Models

| Model | Purpose |
|-------|---------|
| `InstrumentCandle` | Unified candle with OHLCV + orderbook + OI |
| `FamilyCandle` | Cross-instrument family with derived metrics |
| `InstrumentFamily` | Equity → Future + Options mapping |
| `InstrumentType` | EQUITY, FUTURE, OPTION_CE, OPTION_PE, INDEX |
| `DataQuality` | VALID, WARNING, CONFLICT, STALE |

## New Calculators

| Calculator | Purpose |
|------------|---------|
| `AdaptiveVPINCalculator` | Volume-adaptive VPIN |
| `OFICalculator` | Order Flow Imbalance |
| `PCRCalculator` | Put/Call Ratio |
| `FuturesBuildupDetector` | Futures OI patterns |
| `OISignalDetector` | Cross-instrument signals |

## Running

```bash
# Build
mvn clean package -DskipTests

# Run
java -jar target/demo-0.0.1-SNAPSHOT.jar
```

## Kafka Topics

### Input
- `forwardtesting-data` - Raw tick data
- `Orderbook` - Orderbook snapshots
- `OpenInterest` - OI updates

### Output (Family Candles)
- `instrument-candle-1m` - Individual instrument candles
- `family-candle-{1m,2m,3m,5m,15m,30m,1h,2h,4h,1d}` - Family candles

## Logging

Comprehensive logging at all levels:
- `INFO` - Signal generation with 🟢/🔴 indicators
- `DEBUG` - Processing steps and analysis
- `TRACE` - Detailed factor tracking

Enable debug logging:
```properties
logging.level.com.kotsin.consumer.infrastructure.kafka=DEBUG
logging.level.com.kotsin.consumer.domain.service=DEBUG
```

## License

Proprietary - Kotsin Technologies
