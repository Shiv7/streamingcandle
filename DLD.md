# Detailed-Level Design (DLD)
## Market Data Processing System

**Version:** 1.0  
**Date:** October 22, 2025  
**Author:** Engineering Team

---

## 1. Introduction

This document provides the detailed technical design for the Market Data Processing System. It covers class structures, algorithms, data models, state management, and implementation specifics for all components described in the High-Level Design (HLD).

---

## 2. Package Structure

```
com.kotsin.consumer
├── config
│   └── KafkaConfig.java                      # Kafka Streams configuration
├── model
│   ├── TickData.java                          # Input: Tick data model
│   ├── OpenInterest.java                      # Input: OI data model
│   ├── OrderBookSnapshot.java                 # Input: Orderbook model
│   ├── InstrumentCandle.java                  # Output: Per-instrument candle
│   ├── FamilyEnrichedData.java                # Output: Family aggregation
│   ├── Candlestick.java                       # OHLCV data structure
│   ├── MicrostructureData.java                # Microstructure features
│   ├── OrderbookDepthData.java                # Orderbook analytics
│   ├── ImbalanceBarData.java                  # Imbalance bar metrics
│   ├── FamilyAggregatedMetrics.java           # Family-level metrics
│   ├── InstrumentInfo.java                    # Metadata
│   ├── MessageMetadata.java                   # Processing metadata
│   ├── Scrip.java                             # Instrument reference
│   ├── InstrumentFamily.java                  # Family definition
│   └── ScripGroup.java                        # Group of instruments
├── processor
│   ├── UnifiedMarketDataProcessor.java        # Main orchestrator
│   ├── CandleAccumulator.java                 # Candle state accumulator
│   ├── OiAccumulator.java                     # OI state accumulator
│   ├── MicrostructureAccumulator.java         # Microstructure accumulator
│   ├── OrderbookDepthAccumulator.java         # Orderbook accumulator
│   ├── ImbalanceBarAccumulator.java           # Imbalance bar accumulator
│   ├── InstrumentState.java                   # Per-instrument state
│   ├── InstrumentStateManager.java            # State coordinator
│   ├── MultiTimeframeState.java               # Multi-timeframe holder
│   ├── TimeframeStateManager.java             # Timeframe coordinator
│   ├── Timeframe.java                         # Timeframe enum
│   ├── ImbalanceBarState.java                 # Imbalance bar state
│   ├── RunsBarState.java                      # Runs bar state
│   ├── MicrostructureFeatureState.java        # Microstructure state
│   └── WindowRotationService.java             # Window management
├── processor.service
│   ├── CandleEmissionService.java             # Candle emission logic
│   ├── FamilyAggregationService.java          # Family assembly
│   ├── InstrumentKeyResolver.java             # Key extraction
│   ├── TradingHoursValidationService.java     # Trading hours check
│   ├── MarketDataEnrichmentService.java       # Data enrichment
│   ├── MarketDataMergeService.java            # Data merging
│   ├── OrderbookDepthCalculator.java          # Depth calculations
│   ├── MicrostructureMetricsCalculator.java   # Microstructure calc
│   ├── IcebergDetectionService.java           # Iceberg detection
│   └── SpoofingDetectionService.java          # Spoofing detection
├── transformers
│   └── CumToDeltaTransformer.java             # Cumulative to delta
├── extractors
│   ├── TickTimestampExtractor.java            # Tick timestamp
│   ├── OpenInterestTimestampExtractor.java    # OI timestamp
│   ├── InstrumentCandleTimestampExtractor.java # Candle timestamp
│   └── MultiMinuteOffsetTimestampExtractor.java # Multi-minute offset
├── repository
│   ├── ScripGroupRepository.java              # MongoDB repository
│   └── MongoInstrumentFamilyService.java      # Family metadata
├── metrics
│   └── StreamMetrics.java                     # Performance metrics
└── ConsumerApplication.java                   # Spring Boot entry point
```

---

## 3. Core Data Models

### 3.1 TickData Model

```java
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TickData {
    // Market identifiers
    @JsonProperty("Exch") private String exchange;           // "N" (NSE)
    @JsonProperty("ExchType") private String exchangeType;   // "C" (Cash), "D" (Derivatives)
    @JsonProperty("Token") private int token;                 // Unique instrument ID
    @JsonProperty("ScripCode") private String scripCode;      // "RELIANCE", "NIFTY25SEPFUT"
    
    // Price data
    @JsonProperty("LastRate") private double lastRate;        // Last traded price
    @JsonProperty("LastQty") private int lastQuantity;        // Last traded quantity
    @JsonProperty("High") private double high;
    @JsonProperty("Low") private double low;
    @JsonProperty("OpenRate") private double openRate;
    @JsonProperty("PClose") private double previousClose;
    @JsonProperty("AvgRate") private double averageRate;
    
    // Volume data (CUMULATIVE in raw data)
    @JsonProperty("TotalQty") private int totalQuantity;      // Cumulative volume
    @JsonProperty("DeltaQty") private Integer deltaVolume;    // Computed delta (null initially)
    
    // Orderbook snapshot
    @JsonProperty("BidQty") private int bidQuantity;
    @JsonProperty("BidRate") private double bidRate;
    @JsonProperty("OffQty") private int offerQuantity;
    @JsonProperty("OffRate") private double offerRate;
    @JsonProperty("TBidQ") private int totalBidQuantity;
    @JsonProperty("TOffQ") private int totalOfferQuantity;
    
    // Timestamp
    @JsonProperty("TickDt") private String tickDt;            // "/Date(1234567890000)/"
    private long timestamp;                                    // Parsed epoch millis
    
    // Metadata
    @JsonProperty("companyName") private String companyName;
    @JsonProperty("ChgPcnt") private double changePercent;
    
    // Derivatives-specific (may be null for equities)
    private Long openInterest;
    private Long oiChange;
    
    // Transient (not serialized)
    private transient OrderBookSnapshot fullOrderbook;
    
    /**
     * Parses timestamp from TickDt field.
     * CRITICAL: Never uses System.currentTimeMillis() to handle lag correctly.
     */
    public void parseTimestamp() {
        if (tickDt != null && tickDt.startsWith("/Date(")) {
            try {
                this.timestamp = Long.parseLong(tickDt.replaceAll("[^0-9]", ""));
            } catch (NumberFormatException e) {
                this.timestamp = 0; // Let TimestampExtractor handle invalid timestamps
            }
        }
    }
}
```

**Key Design Decisions:**
- **Cumulative Volume**: Raw data contains cumulative `TotalQty`. Must compute delta per tick.
- **Timestamp Handling**: Custom parser for non-standard `/Date(...)` format. Never uses current time.
- **Nullable Fields**: OI/OI-change only present for derivatives.

---

### 3.2 InstrumentCandle Model

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstrumentCandle {
    // Identity
    private String scripCode;
    private String companyName;
    private String instrumentType;                 // EQUITY, FUTURE, OPTION, INDEX
    private String exchange;
    private String exchangeType;
    
    // Window information
    private Long windowStartMillis;
    private Long windowEndMillis;
    private String timeframe;                      // "1m", "2m", "5m", etc.
    
    // OHLCV
    private Double open;
    private Double high;
    private Double low;
    private Double close;
    private Long volume;
    private Integer tickCount;
    
    // Derivatives-specific
    private String optionType;                     // CE, PE (null for non-options)
    private Double strikePrice;
    private String expiry;                         // yyyy-MM-dd
    private Long openInterest;
    private Long oiChange;
    
    // Advanced features (enriched data)
    private MicrostructureData microstructure;
    private OrderbookDepthData orderbookDepth;
    private ImbalanceBarData imbalanceBars;
    
    // Derived metrics
    private Double vwap;                           // Volume-weighted average price
    private Double previousClose;
    private Double changePercent;
    
    // Family relationship
    private String underlyingEquityScripCode;      // For derivatives
    
    // Metadata
    private Long emissionTimestamp;
    private Boolean isValid;                       // Data quality flag
    
    /**
     * Validates candle completeness
     */
    public boolean isValid() {
        return scripCode != null && open != null && close != null && 
               volume != null && volume > 0 && tickCount != null && tickCount > 0;
    }
}
```

**Key Design Decisions:**
- **Polymorphic Design**: Single model for equity, futures, options, index.
- **Enrichment Fields**: Microstructure, orderbook, imbalance bars attached to each candle.
- **Family Linking**: `underlyingEquityScripCode` enables family grouping downstream.

---

### 3.3 FamilyEnrichedData Model

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FamilyEnrichedData {
    // Family identity
    private String familyKey;                      // Underlying equity scripCode or index
    private String familyName;                     // Company name or index name
    private String instrumentType;                 // EQUITY_FAMILY, INDEX_FAMILY, DERIVATIVE_FAMILY
    
    // Window information
    private Long windowStartMillis;
    private Long windowEndMillis;
    private String timeframe;
    
    // Constituent instruments
    private InstrumentCandle equity;               // Spot equity/index
    @Builder.Default
    private List<InstrumentCandle> futures = new ArrayList<>();   // Near-month future
    @Builder.Default
    private List<InstrumentCandle> options = new ArrayList<>();   // Top options by volume
    
    // Aggregated metrics
    private FamilyAggregatedMetrics aggregatedMetrics;
    private MicrostructureData microstructure;     // Family-wide average
    private OrderbookDepthData orderbookDepth;     // Family-wide aggregate
    private ImbalanceBarData imbalanceBars;        // Family-wide aggregate
    
    // Metadata
    private Long processingTimestamp;
    private Integer totalInstrumentsCount;
    private String dataQuality;                    // HIGH, MEDIUM, LOW
    
    /**
     * Calculate total instrument count
     */
    public int calculateTotalCount() {
        int count = 0;
        if (equity != null) count++;
        if (futures != null) count += futures.size();
        if (options != null) count += options.size();
        return count;
    }
}
```

**Key Design Decisions:**
- **Hierarchical Structure**: Equity + Futures + Options nested under family.
- **Aggregation Strategy**: Family-level metrics computed from constituent instruments.
- **Flexibility**: Supports equity-only, derivatives-only, or mixed families.

---

### 3.4 MicrostructureData Model

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MicrostructureData {
    // Core metrics
    private Double ofi;                            // Order Flow Imbalance [-1, 1]
    private Double vpin;                           // Volume-Synchronized PIN [0, 1]
    private Double depthImbalance;                 // (Bid - Ask) / (Bid + Ask) [-1, 1]
    private Double kyleLambda;                     // Price impact coefficient
    
    // Bid-ask metrics
    private Double bidAskSpread;
    private Double midPrice;
    private Double effectiveSpread;
    
    // Flow metrics
    private Long buyVolume;
    private Long sellVolume;
    private Double buyPressure;                    // buyVol / (buyVol + sellVol)
    
    // State
    private Boolean isComplete;                    // Minimum observations met
    private Integer observationCount;
    
    /**
     * Validates metric quality
     */
    public boolean isHighQuality() {
        return isComplete != null && isComplete && 
               ofi != null && vpin != null && kyleLambda != null &&
               observationCount != null && observationCount >= 20;
    }
}
```

**Implementation Notes:**
- **OFI Calculation**: `OFI = (BuyVolume - SellVolume) / (BuyVolume + SellVolume)`
- **VPIN Calculation**: Uses EWMA buckets for volume synchronization
- **Warmup Period**: Requires minimum 20 observations before marking complete

---

### 3.5 OrderbookDepthData Model

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderbookDepthData {
    // Depth levels
    @Builder.Default
    private List<DepthLevel> bidDepth = new ArrayList<>();   // Sorted by price descending
    @Builder.Default
    private List<DepthLevel> askDepth = new ArrayList<>();   // Sorted by price ascending
    
    // Summary statistics
    private Double totalBidVolume;
    private Double totalAskVolume;
    private Double bidAskImbalance;                // (Bid - Ask) / (Bid + Ask)
    private Double weightedMidPrice;
    
    // VWAP
    private Double bidVWAP;
    private Double askVWAP;
    
    // Slopes (liquidity distribution)
    private Double bidDepthSlope;                  // Regression slope of bid depths
    private Double askDepthSlope;
    
    // Anomaly detection
    private IcebergSignal icebergSignal;           // Persistent hidden liquidity
    private SpoofingSignal spoofingSignal;         // Rapid order manipulation
    
    // Metadata
    private Long lastUpdateTimestamp;
    private Boolean isValid;
    
    /**
     * Single depth level
     */
    @Data
    @Builder
    public static class DepthLevel {
        private Integer level;                     // 1-based (1 = best bid/ask)
        private Double price;
        private Integer quantity;
        private Integer numberOfOrders;
        private Double distanceFromMid;            // Basis points
        private Double percentOfTotalDepth;
    }
    
    /**
     * Iceberg detection result
     */
    @Data
    @Builder
    public static class IcebergSignal {
        private Boolean detected;
        private String side;                       // BID, ASK
        private Double hiddenVolume;               // Estimated
        private Integer detectionCount;            // Persistence counter
    }
    
    /**
     * Spoofing detection result
     */
    @Data
    @Builder
    public static class SpoofingSignal {
        private Boolean detected;
        private String pattern;                    // LAYERING, FLIPPING
        private Integer orderPlacementRate;        // Orders/second
        private Double volumeRatio;                // Placed vs executed
    }
}
```

**Implementation Notes:**
- **Depth Profile**: Max 10 levels per side
- **Iceberg Detection**: Tracks persistent mismatches between visible and executed volumes
- **Spoofing Detection**: Detects rapid order placement/cancellation patterns

---

## 4. State Management Architecture

### 4.1 InstrumentState Class

```java
@Data
@Slf4j
public class InstrumentState {
    // Identity
    private String scripCode;
    private String companyName;
    private String instrumentType;
    private String underlyingEquityScripCode;
    
    // Multi-timeframe accumulators
    private final EnumMap<Timeframe, CandleAccumulator> candleAccumulators = new EnumMap<>(Timeframe.class);
    private final EnumMap<Timeframe, OiAccumulator> oiAccumulators = new EnumMap<>(Timeframe.class);
    
    // Feature accumulators (reset per window)
    private final EnumMap<Timeframe, MicrostructureAccumulator> microAccumulators = new EnumMap<>(Timeframe.class);
    private final EnumMap<Timeframe, ImbalanceBarAccumulator> imbAccumulators = new EnumMap<>(Timeframe.class);
    private final EnumMap<Timeframe, OrderbookDepthAccumulator> orderbookAccumulators = new EnumMap<>(Timeframe.class);
    
    // Global orderbook accumulator (NEVER reset - for cross-window detection)
    private OrderbookDepthAccumulator globalOrderbookAccumulator;
    
    // Window tracking
    private final EnumMap<Timeframe, Long> currentWindowEnd = new EnumMap<>(Timeframe.class);
    
    // Statistics
    private Long firstTickTime;
    private Long lastTickTime;
    private Long messageCount = 0L;
    
    /**
     * Process incoming tick data
     */
    public void addTick(TickData tick) {
        if (scripCode == null) {
            scripCode = tick.getScripCode();
            companyName = tick.getCompanyName();
            initializeAccumulators();
        }
        
        if (firstTickTime == null) firstTickTime = tick.getTimestamp();
        lastTickTime = tick.getTimestamp();
        messageCount++;
        
        // Update all timeframe accumulators
        for (Timeframe tf : Timeframe.values()) {
            candleAccumulators.get(tf).addTick(tick);
            
            // Microstructure requires orderbook data
            if (tick.getFullOrderbook() != null) {
                microAccumulators.get(tf).addTick(tick, tick.getFullOrderbook());
                orderbookAccumulators.get(tf).update(tick.getFullOrderbook());
                
                // Update global orderbook (never reset)
                if (globalOrderbookAccumulator == null) {
                    globalOrderbookAccumulator = new OrderbookDepthAccumulator();
                }
                globalOrderbookAccumulator.update(tick.getFullOrderbook());
            }
            
            // Imbalance bars
            imbAccumulators.get(tf).addTick(tick);
        }
    }
    
    /**
     * Finalize candles for window end
     */
    public void forceCompleteWindows(long windowEnd) {
        for (Timeframe tf : Timeframe.values()) {
            if (shouldEmitCandle(tf, windowEnd)) {
                candleAccumulators.get(tf).forceComplete();
                currentWindowEnd.put(tf, windowEnd);
            }
        }
    }
    
    /**
     * Rotate windows and reset feature accumulators
     */
    public void rotateWindows() {
        for (Timeframe tf : Timeframe.values()) {
            // Reset feature accumulators for new window
            microAccumulators.put(tf, new MicrostructureAccumulator());
            imbAccumulators.put(tf, new ImbalanceBarAccumulator());
            orderbookAccumulators.put(tf, new OrderbookDepthAccumulator());
            
            // DO NOT reset global orderbook accumulator
        }
    }
}
```

**Key Design Decisions:**
- **Per-Timeframe Isolation**: Each timeframe has independent accumulators.
- **Global Orderbook State**: Persists across windows for pattern detection.
- **Lazy Initialization**: Accumulators created on first tick to optimize memory.

---

### 4.2 CandleAccumulator Class

```java
@Data
@Slf4j
public class CandleAccumulator {
    private Double open;
    private Double high;
    private Double low;
    private Double close;
    private Long volume = 0L;
    private Integer tickCount = 0;
    
    private Long firstTickTime;
    private Long lastTickTime;
    
    private Double previousClose;
    private Boolean isComplete = false;
    
    /**
     * Add tick to accumulator
     */
    public void addTick(TickData tick) {
        if (open == null) {
            open = tick.getLastRate();
            firstTickTime = tick.getTimestamp();
        }
        
        // Update OHLCV
        high = (high == null) ? tick.getLastRate() : Math.max(high, tick.getLastRate());
        low = (low == null) ? tick.getLastRate() : Math.min(low, tick.getLastRate());
        close = tick.getLastRate();
        
        // Accumulate volume (delta volume from transformer)
        if (tick.getDeltaVolume() != null && tick.getDeltaVolume() > 0) {
            volume += tick.getDeltaVolume();
        }
        
        tickCount++;
        lastTickTime = tick.getTimestamp();
        previousClose = tick.getPreviousClose();
    }
    
    /**
     * Mark candle as complete (called at window close)
     */
    public void forceComplete() {
        isComplete = true;
    }
    
    /**
     * Build Candlestick object
     */
    public Candlestick toCandlestick() {
        if (!isComplete) {
            log.warn("Attempting to build incomplete candle");
        }
        
        return Candlestick.builder()
            .open(open)
            .high(high)
            .low(low)
            .close(close)
            .volume(volume)
            .tickCount(tickCount)
            .build();
    }
}
```

**Implementation Notes:**
- **Initialization**: `open` set on first tick, not pre-initialized.
- **Volume Handling**: Uses delta volume (computed upstream by `CumToDeltaTransformer`).
- **Completion Flag**: Prevents premature emission of partial candles.

---

### 4.3 MicrostructureAccumulator Class

```java
@Data
@Slf4j
public class MicrostructureAccumulator {
    // EWMA parameters
    private static final double EWMA_SPAN = 100.0;
    private static final double ALPHA = 2.0 / (EWMA_SPAN + 1.0);
    
    // Accumulators
    private Double ewmaBuyVolume = 0.0;
    private Double ewmaSellVolume = 0.0;
    private Double ewmaDepthImbalance = 0.0;
    private Double ewmaPriceImpact = 0.0;
    
    // VPIN buckets
    private final List<Double> volumeBuckets = new ArrayList<>();
    private static final int VPIN_BUCKET_COUNT = 50;
    private double currentBucketVolume = 0.0;
    private double targetBucketSize = 0.0;
    
    // Observation count
    private int observationCount = 0;
    private static final int MIN_OBSERVATIONS = 20;
    
    /**
     * Add tick and orderbook update
     */
    public void addTick(TickData tick, OrderBookSnapshot orderbook) {
        if (tick.getDeltaVolume() == null || tick.getDeltaVolume() <= 0) {
            return; // Skip zero-volume ticks
        }
        
        observationCount++;
        
        // Classify trade direction (Lee-Ready algorithm)
        double midPrice = (orderbook.getBestBid() + orderbook.getBestAsk()) / 2.0;
        boolean isBuy = tick.getLastRate() > midPrice;
        
        // Update EWMA for buy/sell volumes
        double buyVol = isBuy ? tick.getDeltaVolume() : 0.0;
        double sellVol = isBuy ? 0.0 : tick.getDeltaVolume();
        
        ewmaBuyVolume = ALPHA * buyVol + (1 - ALPHA) * ewmaBuyVolume;
        ewmaSellVolume = ALPHA * sellVol + (1 - ALPHA) * ewmaSellVolume;
        
        // Update depth imbalance EWMA
        double bidDepth = orderbook.getTotalBidQuantity();
        double askDepth = orderbook.getTotalAskQuantity();
        double depthImb = (bidDepth - askDepth) / (bidDepth + askDepth + 1e-9);
        ewmaDepthImbalance = ALPHA * depthImb + (1 - ALPHA) * ewmaDepthImbalance;
        
        // Update VPIN buckets (volume-synchronized)
        updateVPINBuckets(tick.getDeltaVolume(), isBuy);
        
        // Kyle's Lambda: price impact per unit volume
        if (observationCount > 1) {
            double priceChange = Math.abs(tick.getLastRate() - close);
            double impact = priceChange / (tick.getDeltaVolume() + 1e-9);
            ewmaPriceImpact = ALPHA * impact + (1 - ALPHA) * ewmaPriceImpact;
        }
        
        close = tick.getLastRate();
    }
    
    /**
     * Update VPIN volume buckets
     */
    private void updateVPINBuckets(int volume, boolean isBuy) {
        if (targetBucketSize == 0.0) {
            // Initialize target bucket size (adaptive)
            targetBucketSize = volume * 10.0; // Heuristic
        }
        
        currentBucketVolume += volume;
        
        if (currentBucketVolume >= targetBucketSize) {
            double buyFraction = isBuy ? 1.0 : 0.0;
            volumeBuckets.add(buyFraction);
            currentBucketVolume = 0.0;
            
            // Keep only last N buckets
            if (volumeBuckets.size() > VPIN_BUCKET_COUNT) {
                volumeBuckets.remove(0);
            }
        }
    }
    
    /**
     * Build microstructure data
     */
    public MicrostructureData toMicrostructureData() {
        if (observationCount < MIN_OBSERVATIONS) {
            return MicrostructureData.builder()
                .isComplete(false)
                .observationCount(observationCount)
                .build();
        }
        
        // Calculate OFI
        double ofi = (ewmaBuyVolume - ewmaSellVolume) / (ewmaBuyVolume + ewmaSellVolume + 1e-9);
        
        // Calculate VPIN
        double vpin = calculateVPIN();
        
        return MicrostructureData.builder()
            .ofi(ofi)
            .vpin(vpin)
            .depthImbalance(ewmaDepthImbalance)
            .kyleLambda(ewmaPriceImpact)
            .buyVolume((long) ewmaBuyVolume)
            .sellVolume((long) ewmaSellVolume)
            .isComplete(true)
            .observationCount(observationCount)
            .build();
    }
    
    /**
     * Calculate VPIN from volume buckets
     */
    private double calculateVPIN() {
        if (volumeBuckets.size() < 10) {
            return 0.0; // Not enough data
        }
        
        double sum = 0.0;
        for (Double bucket : volumeBuckets) {
            sum += Math.abs(bucket - 0.5); // Deviation from balanced flow
        }
        
        return sum / volumeBuckets.size();
    }
}
```

**Implementation Notes:**
- **EWMA Smoothing**: Alpha = 0.0196 (span = 100) for noise reduction.
- **VPIN**: Volume-synchronized bucketing with adaptive bucket size.
- **Trade Classification**: Lee-Ready algorithm (compare to mid-price).

---

## 5. Kafka Streams Topology

### 5.1 UnifiedMarketDataProcessor - Stream 1

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class UnifiedMarketDataProcessor {
    
    /**
     * STREAM 1: Per-Instrument Candle Generation
     */
    private void processPerInstrumentStream() {
        Properties props = kafkaConfig.getStreamProperties(appIdPrefix + "-instrument");
        StreamsBuilder builder = new StreamsBuilder();
        
        // Step 1: Add state store for delta volume calculation
        String deltaVolumeStoreName = "instrument-delta-volume-store";
        builder.addStateStore(
            Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(deltaVolumeStoreName),
                Serdes.String(),
                Serdes.Integer()
            )
        );
        
        // Step 2: Read tick stream
        KStream<String, TickData> ticksRaw = builder.stream(
            ticksTopic,
            Consumed.with(Serdes.String(), TickData.serde())
        );
        
        // Step 3: Transform cumulative to delta volume
        KStream<String, TickData> ticks = ticksRaw
            .transform(
                () -> new CumToDeltaTransformer(deltaVolumeStoreName),
                deltaVolumeStoreName
            )
            .filter((key, tick) -> tick != null && tick.getDeltaVolume() != null);
        
        // Step 4: Read and prepare orderbook stream for enrichment
        KStream<String, OrderBookSnapshot> orderbookStream = builder.stream(
            orderbookTopic,
            Consumed.with(Serdes.String(), OrderBookSnapshot.serde())
        );
        
        // Re-key orderbook by token (matches scripCode for all instruments)
        KTable<String, OrderBookSnapshot> orderbookTable = orderbookStream
            .selectKey((k, v) -> v != null && v.getToken() != null ? v.getToken() : k)
            .toTable(Materialized.with(Serdes.String(), OrderBookSnapshot.serde()));
        
        // Step 5: Re-key ticks by instrument key
        KStream<String, TickData> instrumentKeyed = ticks
            .selectKey((k, v) -> keyResolver.getInstrumentKey(v));
        
        // Step 6: Define windowing
        TimeWindows windows = TimeWindows
            .ofSizeAndGrace(Duration.ofMinutes(1), Duration.ofSeconds(10));
        
        // Step 7: Aggregate into InstrumentState
        KTable<Windowed<String>, InstrumentState> aggregated = instrumentKeyed
            .filter((scripCode, tick) -> tradingHoursService.withinTradingHours(tick))
            .groupByKey(Grouped.with(Serdes.String(), TickData.serde()))
            .windowedBy(windows)
            .aggregate(
                InstrumentState::new,
                (scripCode, tick, state) -> {
                    if (state.getScripCode() == null) {
                        String instrumentType = keyResolver.getInstrumentType(tick);
                        String familyKey = keyResolver.getFamilyKey(tick);
                        state.setInstrumentType(instrumentType);
                        state.setUnderlyingEquityScripCode(familyKey);
                    }
                    state.addTick(tick);
                    return state;
                },
                Materialized.<String, InstrumentState, WindowStore<Bytes, byte[]>>as("instrument-state-store")
                    .withKeySerde(Serdes.String())
                    .withValueSerde(new JsonSerde<>(InstrumentState.class))
            );
        
        // Step 8: Suppress until window closes
        KStream<String, InstrumentState> stateStream = aggregated
            .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()))
            .toStream()
            .peek((windowedKey, state) -> state.forceCompleteWindows(windowedKey.window().end()))
            .selectKey((windowedKey, state) -> windowedKey.key());
        
        // Step 9: Enrich with orderbook data
        KStream<String, InstrumentState> enrichedState = stateStream
            .leftJoin(
                orderbookTable,
                (state, orderbook) -> {
                    // Attach orderbook to latest tick (for microstructure)
                    if (orderbook != null && orderbook.isValid()) {
                        // Store in state for feature calculation
                        state.enrichWithOrderbook(orderbook);
                    }
                    return state;
                }
            );
        
        // Step 10: Emit multi-timeframe candles
        enrichedState.foreach((scripCode, state) -> {
            candleEmissionService.emitCandles(state);
        });
        
        // Step 11: Start stream
        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streams.start();
        streamsInstances.put("instrument-stream", streams);
    }
}
```

**Key Implementation Details:**
- **State Store Pattern**: Persistent RocksDB for fault tolerance.
- **Window Suppression**: Ensures only complete windows are emitted.
- **Left Join**: Graceful handling of missing orderbook data.
- **Multi-Timeframe**: Emission service handles all timeframes from single state.

---

### 5.2 CumToDeltaTransformer

```java
public class CumToDeltaTransformer implements Transformer<String, TickData, KeyValue<String, TickData>> {
    
    private ProcessorContext context;
    private KeyValueStore<String, Integer> stateStore;
    private final String storeName;
    
    public CumToDeltaTransformer(String storeName) {
        this.storeName = storeName;
    }
    
    @Override
    public void init(ProcessorContext context) {
        this.context = context;
        this.stateStore = context.getStateStore(storeName);
    }
    
    @Override
    public KeyValue<String, TickData> transform(String key, TickData tick) {
        if (tick == null || tick.getScripCode() == null) {
            return KeyValue.pair(key, null); // Filter out
        }
        
        String scripCode = tick.getScripCode();
        int currentCumulative = tick.getTotalQuantity();
        
        // Retrieve last cumulative volume from state store
        Integer lastCumulative = stateStore.get(scripCode);
        
        if (lastCumulative == null) {
            // First tick for this instrument - store and skip delta calculation
            stateStore.put(scripCode, currentCumulative);
            tick.setDeltaVolume(null); // No delta yet
            return KeyValue.pair(key, tick);
        }
        
        // Calculate delta
        int deltaVolume = currentCumulative - lastCumulative;
        
        // Handle edge cases
        if (deltaVolume < 0) {
            // Reset detected (new trading day or data issue)
            log.warn("Cumulative volume reset detected for {}: last={}, current={}", 
                scripCode, lastCumulative, currentCumulative);
            deltaVolume = currentCumulative; // Use current as delta
        }
        
        // Update state store
        stateStore.put(scripCode, currentCumulative);
        
        // Set delta volume
        tick.setDeltaVolume(deltaVolume);
        
        return KeyValue.pair(key, tick);
    }
    
    @Override
    public void close() {
        // No resources to clean up
    }
}
```

**Implementation Notes:**
- **Stateful Transformation**: Maintains per-instrument cumulative volume.
- **Reset Handling**: Detects negative deltas (new trading day).
- **State Store**: Persisted to RocksDB for recovery.

---

### 5.3 CandleEmissionService

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class CandleEmissionService {
    
    private final KafkaTemplate<String, InstrumentCandle> kafkaTemplate;
    private final InstrumentKeyResolver keyResolver;
    
    // Topic names injected from properties
    @Value("${stream.outputs.candles.1m}") private String candle1mTopic;
    @Value("${stream.outputs.candles.2m}") private String candle2mTopic;
    @Value("${stream.outputs.candles.3m}") private String candle3mTopic;
    @Value("${stream.outputs.candles.5m}") private String candle5mTopic;
    @Value("${stream.outputs.candles.15m}") private String candle15mTopic;
    @Value("${stream.outputs.candles.30m}") private String candle30mTopic;
    
    /**
     * Emit candles for all timeframes from instrument state
     */
    public void emitCandles(InstrumentState state) {
        for (Timeframe timeframe : Timeframe.values()) {
            if (shouldEmit(state, timeframe)) {
                InstrumentCandle candle = buildCandle(state, timeframe);
                if (candle != null && candle.isValid()) {
                    String topic = getTopicForTimeframe(timeframe);
                    kafkaTemplate.send(topic, candle.getScripCode(), candle);
                    log.debug("Emitted candle: {} {} - {}", candle.getScripCode(), timeframe, candle);
                }
            }
        }
        
        // Rotate windows after emission
        state.rotateWindows();
    }
    
    /**
     * Build InstrumentCandle from state
     */
    private InstrumentCandle buildCandle(InstrumentState state, Timeframe timeframe) {
        CandleAccumulator candleAcc = state.getCandleAccumulators().get(timeframe);
        if (candleAcc == null || !candleAcc.getIsComplete()) {
            return null;
        }
        
        Candlestick ohlcv = candleAcc.toCandlestick();
        
        // Build base candle
        InstrumentCandle.InstrumentCandleBuilder builder = InstrumentCandle.builder()
            .scripCode(state.getScripCode())
            .companyName(state.getCompanyName())
            .instrumentType(state.getInstrumentType())
            .underlyingEquityScripCode(state.getUnderlyingEquityScripCode())
            .timeframe(timeframe.getLabel())
            .windowStartMillis(state.getCurrentWindowEnd().get(timeframe) - timeframe.getDurationMillis())
            .windowEndMillis(state.getCurrentWindowEnd().get(timeframe))
            .open(ohlcv.getOpen())
            .high(ohlcv.getHigh())
            .low(ohlcv.getLow())
            .close(ohlcv.getClose())
            .volume(ohlcv.getVolume())
            .tickCount(ohlcv.getTickCount())
            .previousClose(candleAcc.getPreviousClose())
            .emissionTimestamp(System.currentTimeMillis())
            .isValid(true);
        
        // Attach enriched features
        MicrostructureAccumulator microAcc = state.getMicroAccumulators().get(timeframe);
        if (microAcc != null) {
            builder.microstructure(microAcc.toMicrostructureData());
        }
        
        OrderbookDepthAccumulator obAcc = state.getOrderbookAccumulators().get(timeframe);
        if (obAcc != null) {
            builder.orderbookDepth(obAcc.toOrderbookDepthData());
        }
        
        ImbalanceBarAccumulator imbAcc = state.getImbAccumulators().get(timeframe);
        if (imbAcc != null) {
            builder.imbalanceBars(imbAcc.toImbalanceBarData());
        }
        
        // Add OI data if available
        OiAccumulator oiAcc = state.getOiAccumulators().get(timeframe);
        if (oiAcc != null) {
            builder.openInterest(oiAcc.getLatestOI())
                   .oiChange(oiAcc.getOIChange());
        }
        
        return builder.build();
    }
    
    /**
     * Determine if candle should be emitted for given timeframe
     */
    private boolean shouldEmit(InstrumentState state, Timeframe timeframe) {
        // Check if window has completed
        Long windowEnd = state.getCurrentWindowEnd().get(timeframe);
        if (windowEnd == null) return false;
        
        // Check if accumulator is complete
        CandleAccumulator candleAcc = state.getCandleAccumulators().get(timeframe);
        return candleAcc != null && candleAcc.getIsComplete();
    }
    
    /**
     * Map timeframe to output topic
     */
    private String getTopicForTimeframe(Timeframe timeframe) {
        return switch (timeframe) {
            case ONE_MIN -> candle1mTopic;
            case TWO_MIN -> candle2mTopic;
            case THREE_MIN -> candle3mTopic;
            case FIVE_MIN -> candle5mTopic;
            case FIFTEEN_MIN -> candle15mTopic;
            case THIRTY_MIN -> candle30mTopic;
        };
    }
}
```

**Implementation Notes:**
- **Multi-Timeframe**: Single state object produces 6 different candles.
- **Enrichment Attachment**: Microstructure, orderbook, imbalance bars attached to each candle.
- **Window Rotation**: State reset after emission for next window.

---

## 6. Advanced Feature Implementations

### 6.1 Iceberg Detection Algorithm

```java
@Slf4j
public class IcebergDetectionService {
    
    private static final int DETECTION_WINDOW = 100;       // Ticks
    private static final double VOLUME_THRESHOLD = 2.0;    // 2x visible volume
    private static final int MIN_DETECTIONS = 3;           // Persistence requirement
    
    // Historical tracking
    private final Deque<OrderBookSnapshot> orderbookHistory = new LinkedList<>();
    private final Deque<Integer> executedVolumeHistory = new LinkedList<>();
    
    // Detection state
    private int bidIcebergCount = 0;
    private int askIcebergCount = 0;
    private double estimatedBidHiddenVolume = 0.0;
    private double estimatedAskHiddenVolume = 0.0;
    
    /**
     * Update with new orderbook snapshot
     */
    public void update(OrderBookSnapshot orderbook, int executedVolume) {
        orderbookHistory.add(orderbook);
        executedVolumeHistory.add(executedVolume);
        
        // Keep only recent window
        if (orderbookHistory.size() > DETECTION_WINDOW) {
            orderbookHistory.removeFirst();
            executedVolumeHistory.removeFirst();
        }
        
        // Detect icebergs
        detectBidIceberg();
        detectAskIceberg();
    }
    
    /**
     * Detect iceberg on bid side
     */
    private void detectBidIceberg() {
        if (orderbookHistory.size() < 10) return;
        
        // Calculate visible bid volume (top 5 levels)
        double visibleVolume = orderbookHistory.getLast().getBids().stream()
            .limit(5)
            .mapToInt(OrderBookLevel::getQuantity)
            .sum();
        
        // Calculate executed volume at bid (buys)
        double executedAtBid = executedVolumeHistory.stream()
            .filter(vol -> vol > 0) // Positive = buy
            .mapToInt(Integer::intValue)
            .sum();
        
        // Check if executed >> visible (iceberg signature)
        if (executedAtBid > visibleVolume * VOLUME_THRESHOLD) {
            bidIcebergCount++;
            estimatedBidHiddenVolume = executedAtBid - visibleVolume;
            log.debug("Iceberg detected on BID: executed={}, visible={}, hidden={}", 
                executedAtBid, visibleVolume, estimatedBidHiddenVolume);
        } else {
            bidIcebergCount = Math.max(0, bidIcebergCount - 1); // Decay
        }
    }
    
    /**
     * Build iceberg signal
     */
    public OrderbookDepthData.IcebergSignal getIcebergSignal() {
        boolean detected = (bidIcebergCount >= MIN_DETECTIONS) || (askIcebergCount >= MIN_DETECTIONS);
        String side = (bidIcebergCount > askIcebergCount) ? "BID" : "ASK";
        double hiddenVolume = (bidIcebergCount > askIcebergCount) ? estimatedBidHiddenVolume : estimatedAskHiddenVolume;
        
        return OrderbookDepthData.IcebergSignal.builder()
            .detected(detected)
            .side(side)
            .hiddenVolume(hiddenVolume)
            .detectionCount(Math.max(bidIcebergCount, askIcebergCount))
            .build();
    }
}
```

**Algorithm Explanation:**
1. **Signature**: Executed volume significantly exceeds visible orderbook depth.
2. **Window**: Tracks last 100 orderbook snapshots.
3. **Threshold**: Executed > 2x visible volume triggers detection.
4. **Persistence**: Requires 3+ consecutive detections to confirm.

---

### 6.2 Spoofing Detection Algorithm

```java
@Slf4j
public class SpoofingDetectionService {
    
    private static final int RATE_WINDOW_MILLIS = 1000;     // 1 second
    private static final int MIN_PLACEMENT_RATE = 10;       // Orders per second
    private static final double CANCELLATION_RATIO = 0.8;   // 80% cancelled
    
    // Order event tracking
    private final Deque<OrderEvent> recentEvents = new LinkedList<>();
    
    private static class OrderEvent {
        long timestamp;
        String action; // PLACE, CANCEL, EXECUTE
        String side;   // BID, ASK
        int quantity;
    }
    
    /**
     * Update with orderbook changes
     */
    public void update(OrderBookSnapshot current, OrderBookSnapshot previous) {
        if (previous == null) return;
        
        long now = System.currentTimeMillis();
        
        // Detect new orders (increased depth)
        detectNewOrders(current, previous, now);
        
        // Detect cancellations (decreased depth)
        detectCancellations(current, previous, now);
        
        // Clean old events
        removeStaleEvents(now);
    }
    
    /**
     * Detect rapid order placement
     */
    private void detectNewOrders(OrderBookSnapshot current, OrderBookSnapshot previous, long timestamp) {
        // Compare bid side
        int bidIncrease = current.getTotalBidQuantity() - previous.getTotalBidQuantity();
        if (bidIncrease > 0) {
            recentEvents.add(new OrderEvent(timestamp, "PLACE", "BID", bidIncrease));
        }
        
        // Compare ask side
        int askIncrease = current.getTotalAskQuantity() - previous.getTotalAskQuantity();
        if (askIncrease > 0) {
            recentEvents.add(new OrderEvent(timestamp, "PLACE", "ASK", askIncrease));
        }
    }
    
    /**
     * Check for spoofing pattern
     */
    public OrderbookDepthData.SpoofingSignal getSpoofingSignal() {
        long now = System.currentTimeMillis();
        long windowStart = now - RATE_WINDOW_MILLIS;
        
        // Count placements and cancellations in window
        int placements = 0;
        int cancellations = 0;
        int placedVolume = 0;
        int cancelledVolume = 0;
        
        for (OrderEvent event : recentEvents) {
            if (event.timestamp >= windowStart) {
                if ("PLACE".equals(event.action)) {
                    placements++;
                    placedVolume += event.quantity;
                } else if ("CANCEL".equals(event.action)) {
                    cancellations++;
                    cancelledVolume += event.quantity;
                }
            }
        }
        
        // Check spoofing criteria
        boolean highPlacementRate = placements >= MIN_PLACEMENT_RATE;
        double cancellationRatio = placedVolume > 0 ? (double) cancelledVolume / placedVolume : 0.0;
        boolean highCancellationRate = cancellationRatio >= CANCELLATION_RATIO;
        
        boolean detected = highPlacementRate && highCancellationRate;
        String pattern = detected ? (cancellationRatio > 0.9 ? "LAYERING" : "FLIPPING") : null;
        
        return OrderbookDepthData.SpoofingSignal.builder()
            .detected(detected)
            .pattern(pattern)
            .orderPlacementRate(placements)
            .volumeRatio(cancellationRatio)
            .build();
    }
}
```

**Algorithm Explanation:**
1. **Layering**: Rapid order placement (>10/sec) with high cancellation rate (>80%).
2. **Flipping**: Quick order placement followed by cancellation on opposite side.
3. **Window**: 1-second sliding window for rate calculation.

---

## 7. Configuration Management

### 7.1 application.properties

```properties
# Kafka
spring.kafka.bootstrap-servers=localhost:9092
spring.kafka.streams.application-id=unified-market-processor2
spring.kafka.streams.state-dir=/tmp/kafka-streams/streamingcandle

# Processing
spring.kafka.streams.properties.processing.guarantee=at_least_once
spring.kafka.streams.properties.commit.interval.ms=100
spring.kafka.streams.properties.num.stream.threads=2

# Input topics
unified.input.topic.ticks=forwardtesting-data
unified.input.topic.oi=OpenInterest
unified.input.topic.orderbook=Orderbook

# Output topics
stream.outputs.candles.enabled=true
stream.outputs.candles.1m=candle-complete-1m
stream.outputs.candles.2m=candle-complete-2m
stream.outputs.candles.5m=candle-complete-5m
stream.outputs.candles.15m=candle-complete-15m
stream.outputs.candles.30m=candle-complete-30m

# Family aggregation
stream.outputs.familyStructured.enabled=true
stream.outputs.familyStructured.1m=family-structured-1m
stream.outputs.familyStructured.all=family-structured-all

# MongoDB
spring.data.mongodb.uri=mongodb://localhost:27017/tradeIngestion

# Microstructure
microstructure.enabled=true
microstructure.window.size=50
microstructure.min.observations=20

# Imbalance bars
information.bars.vib.enabled=true
information.bars.vib.min.ticks=10
information.bars.vib.warmup.samples=20
```

### 7.2 Environment-Specific Profiles

- **application-local.properties**: Local development (localhost Kafka)
- **application-test.properties**: Integration testing (test Kafka cluster)
- **application-prod.properties**: Production (prod Kafka cluster, increased threads)

---

## 8. Error Handling and Resilience

### 8.1 Deserialization Error Handling

```java
// In KafkaConfig
props.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
          LogAndContinueExceptionHandler.class.getName());
```

**Strategy**: Log deserialization errors and continue processing (skip bad records).

### 8.2 State Store Recovery

- **Changelog Topics**: Automatically created by Kafka Streams.
- **Recovery**: On restart, state stores restored from changelog.
- **Cleanup**: Old state directories cleaned up automatically.

### 8.3 Trading Hours Validation

```java
@Service
public class TradingHoursValidationService {
    
    private static final ZonedDateTime TRADING_START = ZonedDateTime.of(
        2025, 4, 3, 9, 15, 0, 0, ZoneId.of("Asia/Kolkata")
    );
    
    private static final ZonedDateTime TRADING_END = ZonedDateTime.of(
        2025, 4, 3, 15, 30, 0, 0, ZoneId.of("Asia/Kolkata")
    );
    
    public boolean withinTradingHours(TickData tick) {
        ZonedDateTime tickTime = Instant.ofEpochMilli(tick.getTimestamp())
            .atZone(ZoneId.of("Asia/Kolkata"));
        
        return !tickTime.isBefore(TRADING_START) && !tickTime.isAfter(TRADING_END);
    }
}
```

**Purpose**: Filter out pre-market, post-market, and weekend data.

---

## 9. Performance Optimization

### 9.1 State Store Tuning

```java
// Cache size (100 MB)
spring.kafka.streams.properties.statestore.cache.max.bytes=104857600

// Commit interval (100ms)
spring.kafka.streams.properties.commit.interval.ms=100
```

### 9.2 Parallelism

- **Stream Threads**: 2 per instance (CPU-bound)
- **Partition Count**: 12 partitions per topic (3x thread count)
- **Instance Count**: 4 instances in production (auto-scaling)

### 9.3 Serialization Optimization

- **JsonSerde**: Used for flexibility (debug-friendly).
- **Future**: Consider Avro/Protobuf for production (smaller payload, faster).

---

## 10. Testing Strategy

### 10.1 Unit Tests

```java
@Test
public void testCandleAccumulator_OHLCV() {
    CandleAccumulator acc = new CandleAccumulator();
    
    // Add ticks
    acc.addTick(createTick(100.0, 10));
    acc.addTick(createTick(105.0, 20));
    acc.addTick(createTick(98.0, 15));
    
    acc.forceComplete();
    Candlestick candle = acc.toCandlestick();
    
    assertEquals(100.0, candle.getOpen());
    assertEquals(105.0, candle.getHigh());
    assertEquals(98.0, candle.getLow());
    assertEquals(98.0, candle.getClose());
    assertEquals(45L, candle.getVolume());
}
```

### 10.2 Integration Tests

- **Kafka Testcontainers**: Embedded Kafka for topology testing.
- **TopologyTestDriver**: Kafka Streams testing framework.

### 10.3 Coverage Target

- **Minimum**: 90% line coverage (enforced by Jacoco).
- **Critical Paths**: 100% coverage for accumulators, calculators.

---

## 11. Monitoring and Logging

### 11.1 Structured Logging

```xml
<!-- logback.xml -->
<appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
</appender>

<logger name="com.kotsin" level="INFO"/>
<logger name="org.apache.kafka.streams" level="WARN"/>
```

### 11.2 Key Metrics

```java
@Component
public class StreamMetrics {
    private final AtomicLong processedTicks = new AtomicLong();
    private final AtomicLong emittedCandles = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    
    @Scheduled(fixedRate = 10000) // Every 10 seconds
    public void logMetrics() {
        log.info("Metrics: processed={}, emitted={}, errors={}", 
            processedTicks.get(), emittedCandles.get(), errorCount.get());
    }
}
```

---

## 12. Deployment Checklist

### Pre-Deployment
- [ ] Configuration reviewed (bootstrap servers, topics, etc.)
- [ ] State directory configured with sufficient disk space
- [ ] MongoDB connection tested
- [ ] Kafka topics created with correct partition count
- [ ] JVM heap size configured (min 4GB for production)

### Post-Deployment
- [ ] Stream application started successfully
- [ ] Consumer lag monitored (should be < 1000)
- [ ] Output topics receiving data
- [ ] Error logs reviewed (no critical errors)
- [ ] Performance metrics within SLA (P99 < 500ms)

---

## 13. Troubleshooting Guide

### Issue: High Consumer Lag

**Symptoms**: Lag > 10,000 messages  
**Causes**: Insufficient parallelism, slow downstream processing  
**Resolution**:
1. Increase `num.stream.threads` to 4.
2. Add more instances (scale horizontally).
3. Check for slow external calls (MongoDB, logging).

### Issue: State Store Full

**Symptoms**: Disk usage > 90%  
**Causes**: Large state stores, insufficient cleanup  
**Resolution**:
1. Increase disk space.
2. Enable log compaction on changelog topics.
3. Review state retention policies.

### Issue: Deserialization Errors

**Symptoms**: `LogAndContinueExceptionHandler` triggered frequently  
**Causes**: Schema mismatch, corrupt data  
**Resolution**:
1. Validate upstream producer schema.
2. Check Kafka message format.
3. Review `@JsonIgnoreProperties` settings.

---

## 14. Future Enhancements (Technical)

### 14.1 Performance Optimizations
- **Avro Serialization**: Reduce payload size by 50%.
- **RocksDB Tuning**: Custom bloom filters, compression.
- **Async Processing**: Non-blocking external calls.

### 14.2 Feature Additions
- **Adaptive Thresholds**: Dynamic imbalance bar thresholds.
- **Multi-Exchange**: Support BSE, MCX data feeds.
- **ML Pipeline**: Real-time feature vector generation.

### 14.3 Operational Improvements
- **Auto-Scaling**: Kubernetes HPA based on consumer lag.
- **Canary Deployments**: Blue-green deployment strategy.
- **Chaos Engineering**: Fault injection testing.

---

## 15. Appendix

### 15.1 Glossary

| Term | Definition |
|------|------------|
| **Tumbling Window** | Fixed-size, non-overlapping time window |
| **Grace Period** | Additional time allowed for late-arriving events |
| **Changelog Topic** | Kafka topic backing a state store for recovery |
| **EWMA** | Exponentially Weighted Moving Average |
| **VPIN** | Volume-Synchronized Probability of Informed Trading |
| **OFI** | Order Flow Imbalance (buy vs sell pressure) |
| **Kyle's Lambda** | Price impact coefficient (price change per unit volume) |

### 15.2 References

- **Kafka Streams DSL**: https://kafka.apache.org/documentation/streams/developer-guide/dsl-api.html
- **RocksDB Configuration**: https://github.com/facebook/rocksdb/wiki/RocksDB-Tuning-Guide
- **Market Microstructure**: López de Prado, M. (2018). Advances in Financial Machine Learning.
- **Spring Kafka**: https://docs.spring.io/spring-kafka/reference/

---

**Document Control**
- **Version**: 1.0
- **Last Updated**: October 22, 2025
- **Authors**: Engineering Team
- **Review Cycle**: Monthly
- **Next Review**: November 22, 2025