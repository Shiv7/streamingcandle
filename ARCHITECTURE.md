# Streamingcandle Architecture

**Type**: Real-time Trading Analytics System  
**Framework**: Spring Boot 3.2.2 + Kafka Streams  
**Language**: Java 17  
**Purpose**: Process live market data to generate trading signals for Indian equity and commodity markets  

---

## 📊 System Overview

```
┌───────────────────────────────────────────────────────────────────┐
│                          RAW DATA (3 TOPICS)                       │
│  forwardtesting-data | Orderbook | OpenInterest                   │
└──────────────────────┬────────────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│          UNIFIED INSTRUMENT CANDLE PROCESSOR (Stage 1)            │
│  ● Joins tick + orderbook + OI within 1-minute windows           │
│  ● Calculates OHLCV, VPIN, OFI, Kyle's Lambda, trade class       │
│  ● Output: instrument-candle-1m                                   │
└──────────────────────┬───────────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│            FAMILY CANDLE PROCESSOR (Stage 2)                      │
│  ● Groups instruments by family (equity + derivatives)            │
│  ● Calculates PCR, F&O alignment, futures buildup                │
│  ● Output: family-candle-{1m,2m,3m,5m,15m,30m,1h,2h,4h,1d}      │
└──────────────────────┬───────────────────────────────────────────┘
                       │
           ┌───────────┴───────────┬──────────────┬────────────────┐
           ▼                       ▼              ▼                 ▼
    ┌─────────────┐       ┌────────────┐   ┌──────────┐     ┌──────────────┐
    │ IPU/VCP/    │       │ REGIME     │   │ FUDKII   │     │ MASTER ARCH  │
    │ Regime      │       │ (Index +   │   │ Signal   │     │ (FF-1)       │
    │ Processors  │       │  Security) │   │ Detector │     │  Processor   │
    └─────┬───────┘       └──────┬─────┘   └────┬─────┘     └──────┬───────┘
          │                      │              │                    │
          └──────────────────────┴──────────────┴────────────────────┘
                                 ▼
                        ┌──────────────────┐
                        │  MTIS PROCESSOR  │
                        │  (Intelligence   │
                        │   Scorer)        │
                        └────────┬─────────┘
                                 │
                                 ▼
                          TRADING SIGNALS
```

---

## 🏗️ Architecture Layers

### **Layer 1: Infrastructure (Kafka Streams)**

**Location**: `/infrastructure/kafka/`

#### UnifiedInstrumentCandleProcessor (2002 lines)
- **Purpose**: Core data fusion engine
- **Inputs**: 
  - `forwardtesting-data` (ticks, 10 partitions)
  - `Orderbook` (L2 snapshots, 20 partitions - doubled for throughput)
  - `OpenInterest` (OI data, 6 partitions)
- **Output**: `instrument-candle-1m`
- **Key Features**:
  - 60-second tumbling windows with 60s grace period
  - LEFT JOIN semantics (tick is mandatory, orderbook/OI are optional)
  - Event-time processing for replay compatibility
 - Repartitioning to 20 partitions for co-partitioning
  - Adaptive VPIN based on instrument volume
  - Trade classification using Lee-Ready algorithm
  - Orderbook microstructure (OFI, Kyle's Lambda, iceberg detection)
  - Volume Profile (POC, VAH, VAL)
  - Temporal tracking (tick velocity, flash crash detection)

**Critical Implementation Details**:
```java
// 60s window with grace period
TimeWindows windows = TimeWindows.ofSizeAndGrace(
    Duration.ofMinutes(1),
    Duration.ofSeconds(60)
);

// Event-time extraction for replay
consumed.withTimestampExtractor(
    new TickDataTimestampExtractor() // Uses tick.timestamp, not Kafka timestamp
);

// Repartitioning for co-partitioning
ticksRepartitioned = ticks.repartition(
    Repartitioned.withNumberOfPartitions(20)
);

// OI lookup from state store (fallback pattern)
transformValues(() -> new ValueTransformerWithKey<>() {
    ReadOnlyKeyValueStore<String, ?> oiStore;
    void init() {
        oiStore = context.getStateStore("oi-latest-store");
    }
    // Lookup OI from store instead of windowed join
});
```

**Data Aggregates**:
- **TickAggregate**: OHLCV, trade classification, volumeAtPrice histogram
- **OrderbookAggregate**: OFI, depth imbalance, Kyle's Lambda, iceberg detection
- **OIAggregate**: OI OHLC, change metrics

#### FamilyCandleProcessor (1182 lines)
- **Purpose**: Group instruments into families (equity + derivatives)
- **Input**: `instrument-candle-1m`
- **Outputs**: 10 timeframes (family-candle-{1m,2m,3m,5m,15m,30m,1h,2h,4h,1d})
- **Key Features**:
  - 30-second grace period
  - Symbol-based fallback for mapping failures
  - Commodity support (MCX futures as primary)
  - PCR calculation from options chain
  - Futures buildup detection

**Family Resolution Logic**:
```java
// Step 1: Check reverse mapping (derivative → equity)
String equityScripCode = familyDataProvider.getEquityScripCode(scripCode);

// Step 2: If equity/index, self-ID
if (type == EQUITY || type == INDEX) return scripCode;

// Step 3: Symbol-based fallback
String symbolRoot = extractSymbolRoot(companyName);
String equityId = familyDataProvider.findEquityBySymbol(symbolRoot);

// Step 4: Fallback to scripCode (last resort)
return scripCode;
```

**FamilyCandleCollector** (deduplication):
- Merges multiple candles for same instrument in window
- Tracks equity, future, and options separately
- Detects and handles commodity families (MCX)

---

### **Layer 2: Domain Logic (Calculators & Services)**

**Location**: `/domain/calculator/`

#### Market Microstructure
1. **AdaptiveVPINCalculator**
   - Volume-synchronized Probability of Informed Trading
   - Adaptive bucketing based on instrument volume
   - Toxic flow detection

2. **OFICalculator**
   - Order Flow Imbalance (full L2 depth)
   - Tracks bid/ask changes across all levels

3. **TradeClassifier**
   - Lee-Ready algorithm implementation
   - Quote rule → Tick rule fallback
   - Classifies trades as aggressive buy/sell

4. **VolumeProfileCalculator**
   - Point of Control (POC)
   - Value Area High/Low (70% volume)
   - Algorithmic POC expansion

5. **FuturesBuildupDetector**
   - Price-OI divergence detection
   - Bullish/Bearish buildup classification

6. **PCRCalculator**
   - Put-Call Ratio from options chain
   - Sentiment analysis

#### Data Quality
- **TradeOrderbookValidator**: Validates joins
- **BlackScholesGreeks**: Options Greeks calculation

---

### **Layer 3: Signal Generation (Processors)**

**Location**: `/processor/`, `/score/`, `/signal/`, `/masterarch/`

#### IPUProcessor (Institutional Participation & Urgency)
- Detects institutional accumulation/distribution
- Tracks exhaustion levels
- Multi-timeframe analysis (5m, 30m)

#### VCPProcessor (Volume Cluster Profile)
- Identifies high-volume price clusters
- Runway analysis (clear path to targets)
- Support/resistance zones

#### RegimeProcessor (Market Context)
- **IndexRegime**: NIFTY trend, volatility, flow
- **SecurityRegime**: Individual stock regime
- ACL (Anti-Cycle Limiter) for mean reversion detection

#### FU DKIIProcessor (Standalone Strategy)
- Simultaneity detection (5 confirming signals)
- ATR expansion + volume surge + momentum
- Bar patience logic (waits up to 2 bars)

#### MTISProcessor (Multi-Timeframe Intelligence Score)
- **Purpose**: Unified intelligence scorer
- **Inputs**: 10 family candle timeframes + IPU + VCP + Regime + FUDKII
- **Output**: `family-score` (single topic)
- **Features**:
  - Async processing pool (CPU cores × 2)
  - ConcurrentHashMap caches for all inputs
  - F&O alignment from FamilyCandle data (no external API)
  - Multi-timeframe level calculator with 3s timeout

**Cache Design**:
```java
Map<String, FamilyIntelligenceState> stateCache;
Map<String, IndexRegime> indexRegimeCache;
Map<String, SecurityRegime> securityRegimeCache;
Map<String, IPUOutput> ipuCache;
Map<String, FUDKIIOutput> fudkiiCache;
Map<String, MTVCPOutput> vcpCache;
```

#### MasterArchProcessor (FF-1 Strategy)
- **Trigger**: 30m candles (reference timeframe)
- **Outputs**: 
  - `kotsin_FF1`: Final trade decision
  - `masterarch-index-regime`: Index context
  - `masterarch-security-regime`: Security context
- **Features**:
  - Historical candle retrieval from Redis (100 candles)
  - VCP score integration
  - Pivot level tracking
  - OHM (Option Health Module) integration

**Orchestration**:
```java
MasterArchResult result = orchestrator.process(
    familyCandle,
    securityCandles100x30m,  // From Redis
    indexCandles100x30m,     // From Redis
    vcpScore,
    nearestPivot,
    pivotDistance,
    ohmScore
);
```

---

### **Layer 4: Infrastructure Services**

**Location**: `/infrastructure/`

#### Redis Services
1. **RedisPercentileService**
   - 1-year rolling percentiles for volume ROC
   - Used by VolumeCanonicalCalculator

2. **RedisCandleHistoryService**
   - Historical candle storage (up to 100 candles)
   - Timeframe-aware retrieval
   - Used by MasterArchProcessor

3. **RedisOIService**
   - OI data caching

#### API Clients
1. **ScripFinderClient**
   - Instrument metadata (http://13.203.60.173:8102)
   - Circuit breaker pattern (failure threshold: 5)

2. **FivePaisaHistoricalClient**
   - Historical OHLC data for level calculation
   - Multi-timeframe support (1m, 5m, 1d)

#### MongoDB Repositories
- **SignalHistoryRepository**: Historical signals  
- **ScripRepository**: Instrument master data
- **ScripGroupRepository**: Family groupings

---

## 🔗 Data Flow

### **Complete Pipeline Example (NIFTY 50 Stock)**

```
1. RAW TICK (forwardtesting-data)
   token: 12345, price: 1050.00, volume: 100, bid: 1049.50, ask: 1050.50

2. ORDERBOOK (Orderbook topic, ~600 updates/min)
   bids: [{1049.50, 500, 3 orders}, {1049.00, 1000, 5 orders}]
   asks: [{1050.50, 400, 2 orders}, {1051.00, 800, 4 orders}]

3. OPEN INTEREST (OpenInterest topic)
   OI: 1,250,000 (derivative only)

   ↓↓↓ UnifiedInstrumentCandleProcessor (60s window) ↓↓↓

4. INSTRUMENT CANDLE (instrument-candle-1m)
   OHLCV: 1048/1052/1047/1050, vol: 10,500
   VPIN: 0.42 (moderate toxic flow)
   OFI: +125 (more aggressive buying)
   Kyle's Lambda: 0.00015 (price impact)
   Trade Classification: 60% aggressive buy, 40% aggressive sell
   Volume Profile: POC=1050, VAH=1051.50, VAL=1048.50

   ↓↓↓ FamilyCandleProcessor (1m window + grouped) ↓↓↓

5. FAMILY CANDLE (family-candle-5m)
   Equity: Stock OHLCV
   Future: Derivative OHLCV + OI
   Options: 4 options (2 CE, 2 PE)
   PCR: 1.25 (bullish - more put writing)
   OI Signal: BULLISH_BUILD (price ↑, OI ↑)

   ↓↓↓ IPU/VCP/Regime/FUDKII Processors (parallel) ↓↓↓

6. STRATEGY SIGNALS
   IPU: Institutional participation: 0.65, urgency: MODERATE
   VCP: Cluster at 1048-1049 (support), runway clear to 1060
   Regime: Security bullish (0.7), Index neutral (0.5)
   FUDKII: Ignition=true, simultaneity=4/5

   ↓↓↓ MTISProcessor (combines all) ↓↓↓

7. MTIS SCORE (family-score topic)
   MTIS: 72.5 (BULLISH)
   Components: F&O=80, IPU=65, Regime=70, FUDKII=85
   Actionable: true

   ↓↓↓ MasterArchProcessor (FF-1 with 100 historical candles) ↓↓↓

8. FINAL DECISION (kotsin_FF1 topic)
   Decision: BUY
   Final Score: 0.785
   Direction Confidence: 0.68 (bullish)
   Position Size: 2 lots
   Entry: 1050.00, Stop: 1042.00, Target: 1065.00
```

---

## 🗂️ Data Models

### **Core Models** (`/model/`)

#### InstrumentCandle
```java
// Metadata
String scripCode, companyName, exchange, exchangeType;
long windowStartMillis, windowEndMillis;

// OHLCV
double open, high, low, close;
long volume, buyVolume, sellVolume;
double vwap;

// Trade Classification
long aggressiveBuyVolume, aggressiveSellVolume, midpointVolume;
double buyPressure, sellPressure, classificationReliability;

// Microstructure
Double ofi, kyleLambda, depthImbalance;
Double vpin;

// OI
Long openInterest, oiChange;
Double oiChangePercent;

// Quality
DataQuality quality;  // VALID | WARNING | CONFLICT
boolean hasOrderbook, hasOI;
```

#### FamilyCandle
```java
String familyId, symbol, timeframe;
InstrumentCandle equity;    // Primary (or commodity future)
InstrumentCandle future;    // Derivative
List<OptionCandle> options; // Options chain (deduplicated!)

// Computed
Double pcr;                 // Put-Call Ratio
String oiSignal;            // BULLISH_BUILD | BEARISH_UNWIND | etc.
FuturesOptionsAlignment foAlignment;
boolean isCommodity;        // MCX flag
```

#### UnifiedCandle
```java
// OHLCV + All microstructure in one model
// Used for historical storage in Redis
double vpin, ofi, kyleLambda;
Map<Double, Long> volumeAtPrice;
Long oiClose, oiChange;
```

---

## ⚙️ Configuration

### **Kafka Streams**
- **Threads**: 20 (matches Orderbook partitions)
- **Grace Period**: Unified=60s, Family=30s
- **Offset Reset**: earliest (supports replay)
- **State Retention**: 14 days (for late-arriving data)
- **Consumer Optimization**:
  - `max.poll.records`: 2000
  - `fetch.min.bytes`: 2MB
  - `fetch.max.wait.ms`: 50ms

### **Event-Time Processing** (Critical!)
All processors use event-time extraction:
- **TickData**: `tick.getTimestamp()` (from `/Date(epoch)/` format)
- **OrderBook**: `ob.getReceivedTimestamp()`
- **OpenInterest**: `oi.getReceivedTimestamp()`
- **InstrumentCandle**: `candle.getWindowStart Millis()`
- **FamilyCandle**: `family.getWindowStartMillis()`

This ensures:
✅ Replay works correctly (Dec 24 data replays with Dec 24 timestamps)
✅ Window closes based on data time, not wall clock
✅ Consistent behavior between live and replay

### **Topic Naming**
```
Input Topics:
  - forwardtesting-data
  - Orderbook
  - OpenInterest

Derived Topics (1m base):
  - instrument-candle-1m

Family Candle Topics (10 timeframes):
  - family-candle-{1m,2m,3m,5m,15m,30m,1h,2h,4h,1d}

Strategy Topics:
  - ipu-combined
  - vcp-combined
  - regime-index-output
  - regime-security-output
  - fudkii-output

Scoring Topics:
  - family-score (MTIS output)
  - kotsin_FF1 (Final decision)
  - masterarch-index-regime
  - masterarch-security-regime
```

---

## 📈 Performance Characteristics

### **Throughput**
- **Ticks**: ~10,000/sec (peak), ~2,000/sec (average)
- **Orderbook**: ~600 updates/sec (6,000 instruments × 10 updates/min)
- **Windows**: ~6,000 instrument candles/min
- **Family Candles**: ~500 families/min across 10 timeframes = 5,000 candles/min

### **Latency**
- **Stage 1** (Unified): ~60s (window size)
- **Stage 2** (Family): ~30s grace period
- **Stage 3** (Signals): ~100ms (in-memory cache lookups)
- **Stage 4** (MTIS): ~3s (includes HTTP level calc with timeout)
- **Total Pipeline**: ~90s (data → final signal)

### **Memory**
- **VPIN Calculators**: Caffeine cache with 10k max, 2h expiry
- **Previous OI Cache**: 10k instruments, 1 day expiry
- **State Caches**: ConcurrentHashMap per processor

---

## 🔄 Resilience Patterns

### **Fallback Mechanisms**
1. **OI Fallback**: Non-windowed latest store when windowed join fails
2. **Orderbook Fallback**: Latest store when window expires
3. **Symbol Fallback**: Extract symbol from companyName when mapping fails
4. **Quality Propagation**: Worst quality wins (CONFLICT > WARNING > VALID)

### **Exception Handling**
```java
streams.setUncaughtExceptionHandler(exception -> {
    log.error("Uncaught exception", exception);
    return REPLACE_THREAD;  // Auto-restart failed thread
});

streams.setStateListener((newState, oldState) -> {
    if (newState == ERROR) {
        sendAlert(CRITICAL, "Stream ERROR state");
    }
});
```

### **Graceful Degradation**
- Missing orderbook? Emit candle without microstructure metrics
- Missing OI? Emit candle without OI data
- Missing VCP? Use default score of 0.5 (neutral)
- HTTP timeout? Continue without levels (not critical)

---

## 🔍 Observability

### **Logging Levels**
- **INFO**: Actionable signals, errors, lifecycle events
- **DEBUG**: All signals, cache operations, fallback usage
- **TRACE**: Individual record processing

### **Metrics** (`DataQualityMetrics`)
- Candles processed per processor
- Cache hit rates
- Fallback usage counts
- State store performance

### **Trace Logging** (`PipelineTraceLogger`)
- Input received (TICK, OB, OI)
- Signal accepted/rejected with indicators
- Window closures
- Quality issues

---

## 🧪 Testing Approach

### **TestContainers**
- Kafka cluster spin-up
- MongoDB for integration tests

### **Mockito**
- Unit tests for calculators
- Mocked external APIs

### **Jacoco**
- Code coverage tracking
- Coverage checks disabled during migration

---

## 📦 Deployment

### **Build**
```bash
mvn clean package -DskipTests
```

### **Run**
```bash
java -jar target/demo-0.0.1-SNAPSHOT.jar \\
  --spring.profiles.active=production \\
  --spring.kafka.bootstrap-servers=<broker> \\
  --spring.data.mongodb.uri=<mongo_uri> \\
  --spring.data.redis.host=<redis_host>
```

### **Environment Variables**
- `KAFKA_BOOTSTRAP_SERVERS`: Kafka brokers
- `MONGODB_URI`: MongoDB connection
- `REDIS_HOST`: Redis server
- `SCRIPFINDER_API_URL`: Instrument metadata API

---

## 🎯 Key Design Decisions

1. **LEFT JOIN Semantics**: Never discard candles due to missing data
2. **Event-Time Processing**: Replay compatibility is first-class
3. **Repartitioning**: Co-partitioning guarantees for joins
4. **State Store Fallbacks**: Non-windowed stores for late-arriving data
5. **Caffeine Caching**: Prevent VPIN memory leaks with auto-eviction
6. **Async Processing**: Thread pools prevent Kafka consumer blocking
7. **Symbol-Based Fallback**: Recover from missing derivative mappings
8. **Quality Propagation**: Explicit data quality tracking
9. **20 Partitions**: Match highest partition count for parallelism
10. **No External API Blocking**: F&O calculated from FamilyCandle data

---

## 🔮 Technology Stack Summary

| Component | Technology | Version |
|-----------|-----------|---------|
| Framework | Spring Boot | 3.2.2 |
| Streaming | Kafka Streams | 3.x |
| Language | Java | 17 |
| Build | Maven | - |
| Cache | Caffeine | Latest |
| Database | MongoDB | Latest |
| Cache DB | Redis | Latest |
| HTTP Client | OkHttp | 4.12.0 |
| Serialization | Jackson | Spring-managed |
| Lombok | Lombok | 1.18. 36 |
| Testing | JUnit + Mockito + TestContainers | Latest |

---

**Last Updated**: 2026-01-04  
**Codebase Size**: ~15,000 lines of Java code  
**Active Processors**: 12  
**Topics**: 25+  
**Supported Instruments**: 5000+ (NSE equities, derivatives, MCX commodities)
