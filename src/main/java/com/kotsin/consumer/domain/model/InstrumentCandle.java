package com.kotsin.consumer.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * InstrumentCandle - Unified candle model for all instrument types.
 * 
 * REPLACES: EnrichedCandlestick + OrderbookAggregate + OIAggregate
 * 
 * Key Design:
 * - Tick data (OHLCV) is MANDATORY
 * - Orderbook metrics are OPTIONAL (not available for INDEX)
 * - OI metrics are OPTIONAL (only for derivatives)
 * - Use hasOrderbook() and hasOI() to check availability
 * 
 * Data Availability by Type:
 * - INDEX:    OHLCV only
 * - EQUITY:   OHLCV + Orderbook
 * - FUTURE:   OHLCV + Orderbook + OI
 * - OPTIONS:  OHLCV + Orderbook + OI
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class InstrumentCandle {

    // ==================== IDENTITY ====================
    private String scripCode;
    private String symbol;
    private String companyName;
    private String exchange;         // "N" for NSE, "M" for MCX
    private String exchangeType;     // "C" for Cash, "D" for Derivatives
    private InstrumentType instrumentType;

    // ==================== TIMING ====================
    private long windowStartMillis;
    private long windowEndMillis;
    private String timeframe;        // "1m", "2m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "1d"
    private String humanReadableTime;

    // ==================== OHLCV (ALWAYS PRESENT) ====================
    private double open;
    private double high;
    private double low;
    private double close;
    private long volume;
    private long buyVolume;
    private long sellVolume;
    private double vwap;
    private int tickCount;

    // ==================== TRADE CLASSIFICATION (Phase 1.2) ====================
    private Long aggressiveBuyVolume;        // Buyer-initiated trades
    private Long aggressiveSellVolume;       // Seller-initiated trades
    private Long midpointVolume;             // Mid-market trades
    private Double classificationReliability; // % of trades with valid BBO (0-1)
    private Double buyPressure;              // Aggressive buy / total volume
    private Double sellPressure;             // Aggressive sell / total volume

    // ==================== VOLUME PROFILE ====================
    private Map<Double, Long> volumeAtPrice;
    private Double poc;              // Point of Control
    private Double vah;              // Value Area High
    private Double val;              // Value Area Low

    // ==================== IMBALANCE BARS ====================
    private long volumeImbalance;
    private double dollarImbalance;
    private int tickRuns;
    private long volumeRuns;
    private boolean vibTriggered;
    private boolean dibTriggered;
    private boolean trbTriggered;
    private boolean vrbTriggered;

    // ==================== TICK-LEVEL BID/ASK IMBALANCE (P0 Fix) ====================
    private Long sumTotalBidQty;          // Sum of TBidQ from all ticks in window
    private Long sumTotalOffQty;          // Sum of TOffQ from all ticks in window
    private Double tickBidAskImbalance;   // (TBidQ - TOffQ) / (TBidQ + TOffQ), range -1 to +1

    // ==================== OVERNIGHT GAP ANALYSIS (P0 Fix) ====================
    private Double previousClose;         // Previous day's close (from TickData.PClose)
    private Double overnightGap;          // (open - previousClose) / previousClose * 100
    private Boolean isGapUp;              // gap > 0.5%
    private Boolean isGapDown;            // gap < -0.5%

    // ==================== VPIN (Adaptive) ====================
    private double vpin;
    private double vpinBucketSize;   // Adaptive based on daily volume
    private int vpinBucketCount;

    // ==================== ORDERBOOK METRICS (Optional - not for INDEX) ====================
    private boolean orderbookPresent;
    private Long orderbookDataTimestamp;      // Timestamp when orderbook data was actually captured (may differ from windowEndMillis if fallback used)
    private Boolean isOrderbookFallback;      // true if orderbook came from latest store (fallback), false if from windowed join
    private Double ofi;              // Order Flow Imbalance
    private Double kyleLambda;       // Price impact coefficient
    private Double microprice;       // Fair value
    private Double bidAskSpread;
    private Double depthImbalance;
    private Double weightedDepthImbalance;
    private Double averageBidDepth;
    private Double averageAskDepth;
    private Integer spoofingCount;
    private Boolean icebergBidDetected;
    private Boolean icebergAskDetected;

    // ==================== ORDERBOOK DEPTH FRAGMENTATION (Phase 3) ====================
    private Integer totalBidOrders;
    private Integer totalAskOrders;
    private Integer ordersAtBestBid;
    private Integer ordersAtBestAsk;
    private Double avgBidOrderSize;
    private Double avgAskOrderSize;
    private Double depthConcentration;
    private Integer maxDepthLevels;
    private Boolean icebergAtBestBid;
    private Boolean icebergAtBestAsk;

    // ==================== ORDERBOOK UPDATE DYNAMICS (Phase 7) ====================
    private Double spreadVolatility;
    private Double maxSpread;
    private Double minSpread;
    private Integer orderbookUpdateCount;
    private Double spreadChangeRate;
    private Double orderbookMomentum;

    // ==================== ORDER CANCELLATION RATE (Quant Fix P1) ====================
    private Double cancelRate;              // Orders cancelled / total orders observed
    private Long totalOrdersCancelled;      // Total disappeared orders
    private Long totalOrdersObserved;       // Total orders seen

    // ==================== OFI MOMENTUM (Quant Fix P1) ====================
    private Double ofiMomentum;             // dOFI/dt - rate of change of order flow
    private Double averageOfiMomentum;      // Mean OFI momentum over window

    // ==================== MARKET DEPTH SLOPE (Phase 8) ====================
    private Double bidDepthSlope;           // Liquidity curve slope for bid side (qty per price unit)
    private Double askDepthSlope;           // Liquidity curve slope for ask side (qty per price unit)

    // ==================== OI METRICS (Optional - only for derivatives) ====================
    private boolean oiPresent;
    private Long oiDataTimestamp;            // Timestamp when OI data was actually captured (may differ from windowEndMillis)
    private Boolean isOIFallback;            // true if OI came from latest store (always true currently, as OI uses latest store lookup)
    private Long openInterest;
    private Long oiOpen;
    private Long oiHigh;
    private Long oiLow;
    private Long oiClose;
    private Long oiChange;
    private Double oiChangePercent;

    // ==================== OI CORRELATION (Phase 5) ====================
    private Double priceAtOIUpdate;          // Price when OI last updated
    private Long volumeAtOIUpdate;           // Volume when OI last updated
    private Double spreadAtOIUpdate;         // Spread when OI last updated
    private Long oiUpdateLatency;            // Time since last OI update (ms)
    private Integer oiUpdateCount;           // Number of OI updates in window

    // ==================== OPTIONS SPECIFIC (only for OPTION_CE/PE) ====================
    private Double strikePrice;      // Strike price for options
    private String optionType;       // "CE" or "PE"
    private String expiry;           // Expiry date string
    private Integer daysToExpiry;    // Days until expiration
    private Double hoursToExpiry;    // Hours until expiration (more precise)
    private Boolean isNearExpiry;    // True if expiring within 7 days
    
    // PHASE 9: Option Greeks (Black-Scholes)
    private Double delta;            // Price sensitivity to underlying (0-1 for calls, -1-0 for puts)
    private Double gamma;            // Rate of change of delta (always positive)
    private Double vega;             // Sensitivity to volatility (always positive)
    private Double theta;            // Time decay (usually negative)
    private Double impliedVolatility; // Estimated IV from option price (if available)

    // ==================== DATA QUALITY ====================
    private DataQuality quality;
    private String qualityReason;

    // ==================== TEMPORAL METRICS (Phase 2) ====================
    private Long firstTickTimestamp;        // First tick in window (Kafka timestamp)
    private Long lastTickTimestamp;         // Last tick in window (Kafka timestamp)
    private Long minTickGap;                // Min gap between ticks (ms)
    private Long maxTickGap;                // Max gap between ticks (ms)
    private Double avgTickGap;              // Avg gap between ticks (ms)
    private Integer ticksPerSecond;         // Event frequency
    private Double tickAcceleration;        // Change in tick frequency

    // ==================== TRADE SIZE DISTRIBUTION (Phase 4) ====================
    private Long maxTradeSize;              // Largest trade in window
    private Long minTradeSize;              // Smallest trade in window
    private Double avgTradeSize;            // Average trade size
    private Double medianTradeSize;         // Median trade size
    private Integer largeTradeCount;        // Trades > 10x average
    private Double priceImpactPerUnit;      // Price change per unit volume (per million)

    // ==================== CROSS-STREAM LATENCY (Phase 6) ====================
    private Long tickToOrderbookLatency;     // ms between tick and OB update
    private Long tickToOILatency;            // ms between tick and OI update
    private Boolean tickStale;               // Tick data > 5 seconds old
    private Boolean orderbookStale;          // OB data > 5 seconds old
    private Boolean oiStale;                 // OI data > 5 minutes old
    private Long maxDataAge;                 // Age of oldest data point
    private String stalenessReason;          // Why data is stale (if applicable)

    // ==================== LATENCY TRACKING ====================
    private long processingLatencyMs;
    private long maxTickAgeMs;
    private long minTickAgeMs;

    // ==================== SUB-CANDLE SNAPSHOTS (MTF Distribution Fix) ====================
    // Track intra-window patterns for MTF Distribution analysis
    // Created every 10 seconds during tick aggregation in TickAggregate
    private java.util.List<SubCandleSnapshot> subCandleSnapshots;

    /**
     * Sub-candle snapshot for MTF Distribution analysis.
     * Mirrors TickAggregate.SubCandleSnapshot structure.
     */
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SubCandleSnapshot {
        private long eventTime;
        private double open;
        private double high;
        private double low;
        private double close;
        private long volume;
        private long buyVolume;
        private long sellVolume;
        private double vwap;
        private int tickCount;
        private Long oiClose;
    }

    // ==================== P1: VWAP VALIDATION ====================
    private Double exchangeVwap;              // From TickData.averageRate
    private Double vwapDrift;                 // (calculated - exchange) / exchange * 100

    // ==================== TICK-LEVEL SPREAD METRICS (Execution Cost Analysis) ====================
    private Double averageTickSpread;         // Average bid-ask spread across all ticks
    private Double minTickSpread;             // Minimum spread observed
    private Double maxTickSpread;             // Maximum spread observed
    private Double spreadVolatilityTick;      // Spread volatility (std dev)
    private Double tightSpreadPercent;        // % of time spread <= 1 tick (liquidity indicator)

    // ==================== EFFECTIVE SPREAD (Quant Fix P0) ====================
    private Double averageEffectiveSpread;    // 2 * |trade_price - midpoint| - actual execution cost
    private Double minEffectiveSpread;        // Best execution observed
    private Double maxEffectiveSpread;        // Worst execution observed
    private Double priceImprovementRatio;     // % of trades with price better than quoted spread

    // ==================== VWAP BANDS (Trading Signals) ====================
    private Double vwapUpperBand;             // VWAP + 2σ (overbought threshold)
    private Double vwapLowerBand;             // VWAP - 2σ (oversold threshold)
    private Double vwapStdDev;                // Standard deviation of price around VWAP
    private String vwapSignal;                // "OVERBOUGHT" / "OVERSOLD" / "NEUTRAL"

    // ==================== P1: OI VELOCITY ====================
    private Double oiVelocity;                // OI change per minute
    private Double oiAcceleration;            // Velocity change from previous window

    // ==================== P2: ORDERBOOK DEPTH ANALYSIS ====================
    private Double levelWeightedBidDepth;     // Bid depth weighted by 1/(level+1)
    private Double levelWeightedAskDepth;     // Ask depth weighted by 1/(level+1)
    private Double levelWeightedImbalance;    // (bid - ask) / (bid + ask)
    private Double bidFragmentation;          // Total bid qty / total bid orders
    private Double askFragmentation;          // Total ask qty / total ask orders
    private Double institutionalBias;         // askFragmentation / bidFragmentation

    // ==================== P2: TICK INTENSITY ZONES ====================
    private Integer maxTicksInAnySecond;      // Peak tick rate (algo detection)
    private Integer secondsWithTicks;         // Active seconds in window
    private Double tickBurstRatio;            // maxTicksInAnySecond / avgTicksPerSecond
    private Boolean algoActivityDetected;     // burstRatio > 3.0

    // ==================== VALIDATION ====================

    /**
     * Validate OHLCV data integrity
     * FIX: Now checks for NaN and Infinity values
     * @return true if data is valid
     */
    public boolean isValid() {
        // Basic null checks
        if (scripCode == null || scripCode.isEmpty()) return false;
        
        // FIX: Check for NaN/Infinity in price values
        if (Double.isNaN(open) || Double.isInfinite(open)) return false;
        if (Double.isNaN(high) || Double.isInfinite(high)) return false;
        if (Double.isNaN(low) || Double.isInfinite(low)) return false;
        if (Double.isNaN(close) || Double.isInfinite(close)) return false;
        
        // OHLCV validation
        if (high < low) return false;          // High must be >= Low
        if (high < open || high < close) return false;  // High must be highest
        if (low > open || low > close) return false;    // Low must be lowest
        if (volume < 0) return false;          // Volume cannot be negative
        if (close <= 0) return false;          // Price must be positive
        
        return true;
    }
    
    /**
     * Check if a specific field has valid numeric value (not NaN or Infinite)
     */
    public static boolean isValidNumber(Double value) {
        return value != null && !Double.isNaN(value) && !Double.isInfinite(value);
    }

    /**
     * Get validation errors if any
     * FIX: Now includes NaN/Infinity checks
     */
    public String getValidationErrors() {
        StringBuilder errors = new StringBuilder();
        
        if (scripCode == null || scripCode.isEmpty()) {
            errors.append("scripCode is null/empty;");
        }
        // FIX: Check for NaN/Infinity
        if (Double.isNaN(open) || Double.isInfinite(open)) {
            errors.append("open is NaN/Infinite;");
        }
        if (Double.isNaN(high) || Double.isInfinite(high)) {
            errors.append("high is NaN/Infinite;");
        }
        if (Double.isNaN(low) || Double.isInfinite(low)) {
            errors.append("low is NaN/Infinite;");
        }
        if (Double.isNaN(close) || Double.isInfinite(close)) {
            errors.append("close is NaN/Infinite;");
        }
        if (high < low) {
            errors.append("high < low;");
        }
        if (high < open || high < close) {
            errors.append("high is not highest;");
        }
        if (low > open || low > close) {
            errors.append("low is not lowest;");
        }
        if (volume < 0) {
            errors.append("negative volume;");
        }
        if (close <= 0) {
            errors.append("non-positive close;");
        }
        
        return errors.length() > 0 ? errors.toString() : null;
    }

    // ==================== HELPER METHODS ====================

    /**
     * Check if orderbook data is available
     */
    public boolean hasOrderbook() {
        return orderbookPresent && ofi != null;
    }

    /**
     * Check if OI data is available
     */
    public boolean hasOI() {
        return oiPresent && openInterest != null;
    }

    /**
     * Get volume delta (buy - sell)
     */
    public long getVolumeDelta() {
        return buyVolume - sellVolume;
    }

    /**
     * Get volume delta as percentage
     */
    public double getVolumeDeltaPercent() {
        return volume > 0 ? ((double) (buyVolume - sellVolume) / volume) * 100.0 : 0.0;
    }

    /**
     * Get price change percentage
     */
    public double getPriceChangePercent() {
        return open > 0 ? ((close - open) / open) * 100.0 : 0.0;
    }

    /**
     * Check if this is a bullish candle
     */
    public boolean isBullish() {
        return close > open;
    }

    /**
     * Check if this is a bearish candle
     */
    public boolean isBearish() {
        return close < open;
    }

    /**
     * Get candle body size
     */
    public double getBodySize() {
        return Math.abs(close - open);
    }

    /**
     * Get candle range (high - low)
     */
    public double getRange() {
        return high - low;
    }

    /**
     * Update human-readable timestamp
     */
    public void updateHumanReadableTime() {
        if (windowStartMillis > 0) {
            ZonedDateTime zdt = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(windowStartMillis),
                ZoneId.of("Asia/Kolkata")
            );
            this.humanReadableTime = zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
    }

    // ==================== SERDE ====================

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

    public static Serde<InstrumentCandle> serde() {
        return Serdes.serdeFrom(new InstrumentCandleSerializer(), new InstrumentCandleDeserializer());
    }

    public static class InstrumentCandleSerializer implements Serializer<InstrumentCandle> {
        @Override
        public byte[] serialize(String topic, InstrumentCandle data) {
            if (data == null) return null;
            try {
                return MAPPER.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed for InstrumentCandle", e);
            }
        }
    }

    public static class InstrumentCandleDeserializer implements Deserializer<InstrumentCandle> {
        @Override
        public InstrumentCandle deserialize(String topic, byte[] bytes) {
            if (bytes == null) return null;
            try {
                return MAPPER.readValue(bytes, InstrumentCandle.class);
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed for InstrumentCandle", e);
            }
        }
    }
}
