package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Enriched Candlestick with advanced features
 * Pattern: Simple aggregation model managed by Kafka Streams
 *
 * @deprecated This class is part of the LEGACY architecture.
 *             Migrate to {@link com.kotsin.consumer.domain.model.InstrumentCandle} instead.
 *
 *             Why deprecated:
 *             - God class anti-pattern (1040 lines, violates SRP)
 *             - Logic has been extracted to dedicated calculators:
 *               * {@link com.kotsin.consumer.calculator.TradeClassifier}
 *               * {@link com.kotsin.consumer.calculator.VolumeProfileCalculator}
 *               * {@link com.kotsin.consumer.calculator.ImbalanceBarCalculator}
 *               * {@link com.kotsin.consumer.domain.calculator.AdaptiveVPINCalculator}
 *             - New architecture uses InstrumentCandle + FamilyCandle
 *
 *             Migration timeline: Sunset Q2 2025
 *
 * Features:
 * - OHLCV (basic candle data)
 * - Buy/Sell Volume separation
 * - VWAP calculation
 * - Imbalance Bars (VIB, DIB, TRB, VRB with EWMA thresholds)
 * - Volume Profile (POC, Value Area)
 */
@Deprecated(since = "2.0.0", forRemoval = true)
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EnrichedCandlestick {

    // ========== Basic OHLCV ==========
    private double open;
    private double high;
    private double low;
    private double close;
    private long volume;

    // ========== Metadata ==========
    private String exchange;
    private String exchangeType;
    private String companyName;
    private String scripCode;

    // ========== Window timing ==========
    private long windowStartMillis;
    private long windowEndMillis;
    private String humanReadableStartTime;
    private String humanReadableEndTime;

    // ========== Buy/Sell Volume Separation ==========
    private long buyVolume;
    private long sellVolume;
    private double priceVolumeSum;  // For VWAP calculation
    private double vwap;  // Volume-Weighted Average Price (cached)
    private int tickCount;

    // ========== Imbalance Bars State ==========
    private long volumeImbalance;
    private double dollarImbalance;
    private int tickRuns;
    private long volumeRuns;
    private String currentDirection = "NEUTRAL";
    private double expectedVolumeImbalance = 1000.0;
    private double expectedDollarImbalance = 100000.0;
    private double expectedTickRuns = 10.0;
    private double expectedVolumeRuns = 5000.0;
    private static volatile double IMB_EWMA_ALPHA = 0.1;
    private static volatile double IMB_Q_ZSCORE = 1.645; // ~Q95

    // ========== Volume Profile State ==========
    private Map<Double, Long> volumeAtPrice = new HashMap<>();
    private Double lowestPrice;
    private Double highestPrice;
    private static volatile double DEFAULT_TICK_SIZE = 0.05;
    private static volatile double DERIV_TICK_SIZE = 0.05;
    private static final double VALUE_AREA_PERCENTAGE = 0.70;

    // ========== VPIN State (Volume-Synchronized PIN) ==========
    private static final int VPIN_MAX_BUCKETS = 50;
    private static final double VPIN_INITIAL_BUCKET_SIZE = 10000.0;
    private static final double VPIN_ADAPTIVE_ALPHA = 0.05;

    private double vpinBucketSize = VPIN_INITIAL_BUCKET_SIZE;
    private java.util.List<VPINBucket> vpinBuckets = new java.util.ArrayList<>();
    private double vpinCurrentBucketVolume = 0.0;
    private double vpinCurrentBucketBuyVolume = 0.0;
    private double vpin = 0.0;

    // ========== Imbalance Bar Emission Tracking ==========
    private boolean vibTriggered = false;
    private boolean dibTriggered = false;
    private boolean trbTriggered = false;
    private boolean vrbTriggered = false;
    private long lastVibTriggerTime = 0L;
    private long lastDibTriggerTime = 0L;
    private long lastTrbTriggerTime = 0L;
    private long lastVrbTriggerTime = 0L;

    // ========== Processing State (persisted to state store) ==========
    // BUG-008 FIX: Remove transient to preserve lastPrice for tick-rule classification
    private Double lastPrice; // needed for trade classification across state store operations
    private boolean openInitialized = false;
    private boolean highInitialized = false;
    private boolean lowInitialized = false;
    private long openSourceTs = Long.MAX_VALUE;
    private long closeSourceTs = Long.MIN_VALUE;
    private int priceTickCount = 0;
    // EWMA stats for adaptive quantile thresholds
    // BUG-007 FIX: Remove @JsonIgnore to persist EWMA state across windows
    private double vibEwmaMean = 0.0, vibEwmaSq = 0.0;
    private double dibEwmaMean = 0.0, dibEwmaSq = 0.0;
    private double trbEwmaMean = 0.0, trbEwmaSq = 0.0;
    private double vrbEwmaMean = 0.0, vrbEwmaSq = 0.0;

    // BUG-006 FIX: Add instance-level tick size
    private double instrumentTickSize = 0.0;

    // BUG-016 FIX: Add latency tracking fields
    private long processingLatencyMs = 0;  // Time from tick to candle emission
    private long maxTickAgeMs = 0;         // Age of oldest tick in window
    private long minTickAgeMs = 0;         // Age of newest tick in window

    /**
     * Creates a new empty enriched candlestick with default values.
     */
    public EnrichedCandlestick() {
        this.open = 0;
        this.high = 0.0;
        this.low = 0.0;
        this.close = 0;
        this.volume = 0;
        this.buyVolume = 0;
        this.sellVolume = 0;
        this.priceVolumeSum = 0.0;
        this.vwap = 0.0;
        this.tickCount = 0;
        // initialize expected thresholds from configured defaults
        this.expectedVolumeImbalance = INIT_EXPECTED_VIB;
        this.expectedDollarImbalance = INIT_EXPECTED_DIB;
        this.expectedTickRuns = INIT_EXPECTED_TRB;
        this.expectedVolumeRuns = INIT_EXPECTED_VRB;
    }

    // ---------------------- Adaptive configuration ----------------------
    // BUG-013 FIX: Use AtomicReference for thread-safe configuration
    private static final java.util.concurrent.atomic.AtomicReference<ImbalanceConfig> CONFIG =
        new java.util.concurrent.atomic.AtomicReference<>(new ImbalanceConfig());

    /**
     * Thread-safe configuration holder
     */
    public static class ImbalanceConfig {
        public final double ewmaAlpha;
        public final double initVib;
        public final double initDib;
        public final double initTrb;
        public final double initVrb;
        public final double classifyMinAbs;
        public final double classifyBps;
        public final double classifySpreadMult;
        public final double defaultTickSize;
        public final double derivTickSize;
        public final double imbZScore;

        public ImbalanceConfig() {
            this(0.1, 1000.0, 100000.0, 10.0, 5000.0, 0.01, 0.0001, 0.15, 0.05, 0.05, 1.645);
        }

        public ImbalanceConfig(double ewmaAlpha, double initVib, double initDib, double initTrb, double initVrb,
                              double classifyMinAbs, double classifyBps, double classifySpreadMult,
                              double defaultTickSize, double derivTickSize, double imbZScore) {
            this.ewmaAlpha = ewmaAlpha;
            this.initVib = initVib;
            this.initDib = initDib;
            this.initTrb = initTrb;
            this.initVrb = initVrb;
            this.classifyMinAbs = classifyMinAbs;
            this.classifyBps = classifyBps;
            this.classifySpreadMult = classifySpreadMult;
            this.defaultTickSize = defaultTickSize;
            this.derivTickSize = derivTickSize;
            this.imbZScore = imbZScore;
        }
    }

    public static void configure(double ewmaAlpha,
                                 double initVib, double initDib, double initTrb, double initVrb,
                                 double classifyMinAbs, double classifyBps, double classifySpreadMult,
                                 double defaultTick, double derivTick,
                                 double imbZScore) {
        CONFIG.set(new ImbalanceConfig(ewmaAlpha, initVib, initDib, initTrb, initVrb,
                                       classifyMinAbs, classifyBps, classifySpreadMult,
                                       defaultTick, derivTick, imbZScore));
    }

    // Helper methods to access configuration
    private static ImbalanceConfig getConfig() {
        return CONFIG.get();
    }

    // Initial expected values as static so constructor can use current config
    private static volatile double INIT_EXPECTED_VIB = 1000.0;
    private static volatile double INIT_EXPECTED_DIB = 100000.0;
    private static volatile double INIT_EXPECTED_TRB = 10.0;
    private static volatile double INIT_EXPECTED_VRB = 5000.0;

    /**
     * Update using event-time and delta volume (for raw TickData → 1m).
     * Deterministic: open = price at min(event_ts), close = price at max(event_ts).
     * 
     * CRITICAL: This ONE method updates ALL features:
     * - OHLC
     * - Volume (buy/sell separation)
     * - VWAP
     * - Imbalance Bars
     * - Volume Profile
     */
    public void updateWithDelta(TickData tick) {
        // Always allow price-only OHLC updates to support instruments
        // that do not provide reliable per-tick volumes (e.g., some ETFs/indices)
        updateWithDelta(tick, true);
    }

    /**
     * Update using event-time and delta volume, with option to allow price-only OHLC updates
     * for instruments that do not carry trade sizes (e.g., indices).
     */
    public void updateWithDelta(TickData tick, boolean allowPriceOnlyOhlc) {
        long ts = tick.getTimestamp();
        double px = tick.getLastRate();

        Integer dv = tick.getDeltaVolume();
        boolean hasTrade = (dv != null && dv > 0) || tick.getLastQuantity() > 0;

        // ========== OHLC by event time ==========
        if (hasTrade || allowPriceOnlyOhlc) {
            // Count price tick when price changes materially or first observation
            if (lastPrice == null || Math.abs(px - lastPrice) >= getClassificationThreshold(px, tick)) {
                priceTickCount++;
            }
            if (!openInitialized || (ts > 0 && ts < openSourceTs)) {
                open = px;
                openInitialized = true;
                if (ts > 0) {
                    openSourceTs = ts;
                }
            } else if (!openInitialized) {
                open = px;
                openInitialized = true;
            }

            if (ts >= closeSourceTs) {
                close = px;
                closeSourceTs = ts;
            }

            if (!highInitialized || px > high) {
                high = px;
                highInitialized = true;
            }

            if (!lowInitialized || px < low) {
                low = px;
                lowInitialized = true;
            }
        }

        // ========== Volume (delta, buy/sell separation) ==========
        if (dv != null && dv > 0) {
            volume += dv;
            priceVolumeSum += px * dv;
            tickCount++;

            // Classify trade as buy or sell
            boolean isBuy = classifyTrade(tick, px);
            if (isBuy) {
                buyVolume += dv;
            } else {
                sellVolume += dv;
            }

            // ========== Imbalance Bars Update ==========
            updateImbalanceBars(tick, px, dv, isBuy, ts);

            // ========== Volume Profile Update ==========
            updateVolumeProfile(px, dv);

            // ========== VPIN Update ==========
            updateVPIN(dv, isBuy);

            // ========== Calculate VWAP ==========
            vwap = volume > 0 ? priceVolumeSum / volume : 0.0;
        }

        // ========== Metadata (idempotent) ==========
        if (companyName == null) companyName = tick.getCompanyName();
        if (scripCode == null) scripCode = tick.getScripCode();
        exchange = tick.getExchange();
        if (exchangeType == null) exchangeType = tick.getExchangeType();

        if (hasTrade || allowPriceOnlyOhlc) {
            lastPrice = px;
        }
    }

    /**
     * Classify trade as buy or sell using quote-rule then tick-rule
     */
    private boolean classifyTrade(TickData tick, double currentPrice) {
        double bidPrice = tick.getBidRate();
        double askPrice = tick.getOfferRate();

        double threshold = getClassificationThreshold(currentPrice, tick);

        // Quote rule: compare to bid/ask
        if (bidPrice > 0 && askPrice > 0) {
            if (Math.abs(currentPrice - askPrice) <= threshold) {
                return true;  // At ask = buy
            } else if (Math.abs(currentPrice - bidPrice) <= threshold) {
                return false;  // At bid = sell
            }
        }

        // Tick rule: compare to last price
        if (lastPrice != null && Math.abs(currentPrice - lastPrice) > threshold) {
            return currentPrice > lastPrice;
        }

        // Default: sell (avoid buy inflation)
        return false;
    }

    private double getClassificationThreshold(double currentPrice, TickData tick) {
        ImbalanceConfig cfg = getConfig();
        double base = Math.max(cfg.classifyMinAbs, Math.abs(currentPrice) * cfg.classifyBps);
        double spreadTerm = 0.0;
        if (tick != null && tick.getOfferRate() > 0 && tick.getBidRate() > 0) {
            double spread = Math.max(0.0, tick.getOfferRate() - tick.getBidRate());
            spreadTerm = cfg.classifySpreadMult * spread;
        }
        double tickTerm = getEffectiveTickSize();
        return Math.max(base, Math.max(spreadTerm, tickTerm));
    }

    /**
     * BUG-006 FIX: Set instrument-specific tick size
     */
    public void setInstrumentTickSize(double tickSize) {
        this.instrumentTickSize = tickSize;
    }

    // BUG-005 FIX: Track run length separately for proper accumulation
    private int currentRunLength = 0;
    private long currentVolumeRun = 0L;

    /**
     * Update imbalance bars (VIB, DIB, TRB, VRB) with EWMA thresholds
     * BUG-005 FIX: Properly accumulate tick runs and volume runs
     */
    private void updateImbalanceBars(TickData tick, double price, int deltaVolume, boolean isBuy, long eventTime) {
        String direction = isBuy ? "BUY" : "SELL";
        int directionSign = isBuy ? 1 : -1;

        // Volume Imbalance (VIB) - cumulative signed volume
        long signedVolume = deltaVolume * directionSign;
        volumeImbalance += signedVolume;

        // Dollar Imbalance (DIB) - cumulative signed dollar volume
        double dollarVolume = (double) deltaVolume * price;
        dollarImbalance += dollarVolume * directionSign;

        // Tick Runs (TRB) - count CONSECUTIVE same-direction ticks
        // BUG-005 FIX: Accumulate completed runs, don't reset
        if (direction.equals(currentDirection)) {
            currentRunLength++;
        } else {
            // Direction changed - add completed run to total
            tickRuns += currentRunLength;
            currentRunLength = 1;
            currentDirection = direction;
        }

        // Volume Runs (VRB) - volume in CONSECUTIVE same-direction trades
        // BUG-005 FIX: Accumulate completed runs, don't reset
        if (direction.equals(currentDirection)) {
            currentVolumeRun += deltaVolume;
        } else {
            volumeRuns += currentVolumeRun;
            currentVolumeRun = deltaVolume;
        }

        // Check thresholds and update EWMA
        checkAndUpdateThresholds(eventTime);
    }

    /**
     * Check imbalance bar thresholds and update EWMA estimates
     * NOW WITH BAR EMISSION TRACKING!
     * BUG-001 FIX: Properly update EWMA for each imbalance type
     */
    private void checkAndUpdateThresholds(long eventTime) {
        long currentTime = eventTime > 0 ? eventTime : System.currentTimeMillis();
        ImbalanceConfig cfg = getConfig();

        // Update EWMA for each imbalance type
        updateVibEwma(Math.abs((double) volumeImbalance));
        updateDibEwma(Math.abs(dollarImbalance));
        updateTrbEwma(Math.abs((double) tickRuns));
        updateVrbEwma(Math.abs((double) volumeRuns));

        // Calculate thresholds from updated EWMA
        expectedVolumeImbalance = getQuantileThreshold(vibEwmaMean, vibEwmaSq, INIT_EXPECTED_VIB, cfg.imbZScore);
        expectedDollarImbalance = getQuantileThreshold(dibEwmaMean, dibEwmaSq, INIT_EXPECTED_DIB, cfg.imbZScore);
        expectedTickRuns = getQuantileThreshold(trbEwmaMean, trbEwmaSq, INIT_EXPECTED_TRB, cfg.imbZScore);
        expectedVolumeRuns = getQuantileThreshold(vrbEwmaMean, vrbEwmaSq, INIT_EXPECTED_VRB, cfg.imbZScore);

        // VIB threshold check
        if (Math.abs(volumeImbalance) >= expectedVolumeImbalance) {
            vibTriggered = true;  // Mark that VIB bar was triggered
            lastVibTriggerTime = currentTime;  // Track when
            volumeImbalance = 0L;  // Reset after bar emission
        }

        // DIB threshold check
        if (Math.abs(dollarImbalance) >= expectedDollarImbalance) {
            dibTriggered = true;  // Mark that DIB bar was triggered
            lastDibTriggerTime = currentTime;  // Track when
            dollarImbalance = 0L;
        }

        // TRB threshold check
        if (Math.abs(tickRuns) >= expectedTickRuns) {
            trbTriggered = true;  // Mark that TRB bar was triggered
            lastTrbTriggerTime = currentTime;  // Track when
            tickRuns = 0;
        }

        // VRB threshold check
        if (Math.abs(volumeRuns) >= expectedVolumeRuns) {
            vrbTriggered = true;  // Mark that VRB bar was triggered
            lastVrbTriggerTime = currentTime;  // Track when
            volumeRuns = 0L;
        }
    }

    /**
     * BUG-001 FIX: Explicit per-type EWMA update methods
     * These replace the broken updateEwma() method that compared primitive doubles by value
     */
    private void updateVibEwma(double x) {
        ImbalanceConfig cfg = getConfig();
        vibEwmaMean = cfg.ewmaAlpha * x + (1 - cfg.ewmaAlpha) * vibEwmaMean;
        vibEwmaSq = cfg.ewmaAlpha * x * x + (1 - cfg.ewmaAlpha) * vibEwmaSq;
    }

    private void updateDibEwma(double x) {
        ImbalanceConfig cfg = getConfig();
        dibEwmaMean = cfg.ewmaAlpha * x + (1 - cfg.ewmaAlpha) * dibEwmaMean;
        dibEwmaSq = cfg.ewmaAlpha * x * x + (1 - cfg.ewmaAlpha) * dibEwmaSq;
    }

    private void updateTrbEwma(double x) {
        ImbalanceConfig cfg = getConfig();
        trbEwmaMean = cfg.ewmaAlpha * x + (1 - cfg.ewmaAlpha) * trbEwmaMean;
        trbEwmaSq = cfg.ewmaAlpha * x * x + (1 - cfg.ewmaAlpha) * trbEwmaSq;
    }

    private void updateVrbEwma(double x) {
        ImbalanceConfig cfg = getConfig();
        vrbEwmaMean = cfg.ewmaAlpha * x + (1 - cfg.ewmaAlpha) * vrbEwmaMean;
        vrbEwmaSq = cfg.ewmaAlpha * x * x + (1 - cfg.ewmaAlpha) * vrbEwmaSq;
    }

    private double getQuantileThreshold(double mean, double sqMean, double floor, double zScore) {
        double var = Math.max(0.0, sqMean - mean * mean);
        double sigma = Math.sqrt(var);
        double q = mean + zScore * sigma;
        return Math.max(floor, q);
    }

    /**
     * Update volume profile (POC and Value Area calculation)
     */
    private void updateVolumeProfile(double price, long volume) {
        if (volume <= 0) return;

        // Round price to instrument tick size using BigDecimal to avoid float artifacts
        double tickSize = getEffectiveTickSize();
        java.math.BigDecimal p = java.math.BigDecimal.valueOf(price);
        java.math.BigDecimal step = java.math.BigDecimal.valueOf(tickSize);
        java.math.BigDecimal roundedBd = p.divide(step, 0, java.math.RoundingMode.HALF_UP)
                .multiply(step)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        double rounded = roundedBd.doubleValue();

        volumeAtPrice.merge(rounded, volume, Long::sum);

        // Update price range
        if (lowestPrice == null || rounded < lowestPrice) lowestPrice = rounded;
        if (highestPrice == null || rounded > highestPrice) highestPrice = rounded;
    }

    /**
     * Determine tick size for instrument.
     * BUG-006 FIX: Use instrument-specific tick size if available
     */
    private double getEffectiveTickSize() {
        if (instrumentTickSize > 0) {
            return instrumentTickSize;  // Use instrument-specific tick size if set
        }
        // Fallback to defaults
        ImbalanceConfig cfg = getConfig();
        if ("D".equalsIgnoreCase(exchangeType) || "F".equalsIgnoreCase(exchangeType) || "O".equalsIgnoreCase(exchangeType)) {
            return cfg.derivTickSize; // common for derivatives/options
        }
        return cfg.defaultTickSize;
    }

    /**
     * Re-bin volume profile to a new tick size (post-aggregation normalization).
     */
    public void rebinVolumeProfile(double newTickSize) {
        if (newTickSize <= 0 || volumeAtPrice.isEmpty()) return;
        java.util.Map<Double, Long> rebinned = new java.util.HashMap<>();
        for (java.util.Map.Entry<Double, Long> e : volumeAtPrice.entrySet()) {
            double price = e.getKey();
            long vol = e.getValue();
            java.math.BigDecimal p = java.math.BigDecimal.valueOf(price);
            java.math.BigDecimal step = java.math.BigDecimal.valueOf(newTickSize);
            java.math.BigDecimal roundedBd = p.divide(step, 0, java.math.RoundingMode.HALF_UP)
                    .multiply(step)
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            double rounded = roundedBd.doubleValue();
            rebinned.merge(rounded, vol, Long::sum);
        }
        this.volumeAtPrice = rebinned;
        // Update range
        this.lowestPrice = null;
        this.highestPrice = null;
        for (Double pr : rebinned.keySet()) {
            if (lowestPrice == null || pr < lowestPrice) lowestPrice = pr;
            if (highestPrice == null || pr > highestPrice) highestPrice = pr;
        }
    }

    /**
     * Merges another EnrichedCandlestick into this one.
     * Used when building multi-minute candles from smaller timeframe candles.
     * 
     * CRITICAL: This aggregates pre-computed candles (2m from 1m, 5m from 1m, etc.)
     * FIXED: Now tracks window end time to handle out-of-order candles correctly
     *
     * @param other The candle to merge into this one
     */
    public void updateCandle(EnrichedCandlestick other) {
        if (other == null) {
            return;
        }

        boolean otherHasPrice = !(Double.compare(other.high, 0.0) == 0
                && Double.compare(other.low, 0.0) == 0
                && Double.compare(other.open, 0.0) == 0
                && Double.compare(other.close, 0.0) == 0);

        long otherStart = other.windowStartMillis;
        long otherEnd = other.windowEndMillis;

        if (otherStart > 0 && (this.windowStartMillis == 0 || otherStart < this.windowStartMillis)) {
            this.windowStartMillis = otherStart;
            this.humanReadableStartTime = other.humanReadableStartTime;
        } else if (this.windowStartMillis == 0 && otherStart > 0) {
            this.windowStartMillis = otherStart;
            this.humanReadableStartTime = other.humanReadableStartTime;
        }

        if (otherEnd > 0 && (this.windowEndMillis == 0 || otherEnd > this.windowEndMillis)) {
            this.windowEndMillis = otherEnd;
            this.humanReadableEndTime = other.humanReadableEndTime;
        } else if (this.windowEndMillis == 0 && otherEnd > 0) {
            this.windowEndMillis = otherEnd;
            this.humanReadableEndTime = other.humanReadableEndTime;
        }

        // BUG-FIX: Use actual openSourceTs from source candle, NOT window boundary
        // This ensures we pick the OPEN from the candle with the earliest ACTUAL tick, not earliest window
        long candidateOpenTs = other.openSourceTs > 0 ? other.openSourceTs : 
                              (otherStart > 0 ? otherStart : otherEnd);
        if (otherHasPrice && (!this.openInitialized
                || (candidateOpenTs > 0 && candidateOpenTs < this.openSourceTs))) {
            this.open = other.open;
            this.openInitialized = true;
            if (candidateOpenTs > 0) {
                this.openSourceTs = candidateOpenTs;
            }
        }

        // BUG-FIX: Use actual closeSourceTs from source candle for CLOSE selection
        long candidateCloseTs = other.closeSourceTs > 0 ? other.closeSourceTs :
                               (otherEnd > 0 ? otherEnd : otherStart);
        if (otherHasPrice && (candidateCloseTs >= this.closeSourceTs)) {
            this.close = other.close;
            this.closeSourceTs = candidateCloseTs;
        }

        if (otherHasPrice) {
            if (!this.highInitialized || other.high > this.high) {
                this.high = other.high;
                this.highInitialized = true;
            }
            if (!this.lowInitialized || other.low < this.low) {
                this.low = other.low;
                this.lowInitialized = true;
            }
        }

        this.volume += other.volume;
        this.buyVolume += other.buyVolume;
        this.sellVolume += other.sellVolume;
        this.priceVolumeSum += other.priceVolumeSum;
        this.tickCount += other.tickCount;

        this.vwap = this.volume > 0 ? this.priceVolumeSum / this.volume : 0.0;

        this.volumeImbalance += other.volumeImbalance;
        this.dollarImbalance += other.dollarImbalance;

        // BUG-019 FIX: Handle null volumeAtPrice
        if (other.volumeAtPrice != null) {
            other.volumeAtPrice.forEach((price, vol) ->
                    this.volumeAtPrice.merge(price, vol, Long::sum));
        }

        if (other.lowestPrice != null) {
            this.lowestPrice = (this.lowestPrice == null)
                    ? other.lowestPrice
                    : Math.min(this.lowestPrice, other.lowestPrice);
        }
        if (other.highestPrice != null) {
            this.highestPrice = (this.highestPrice == null)
                    ? other.highestPrice
                    : Math.max(this.highestPrice, other.highestPrice);
        }

        this.exchange = other.exchange;
        if (other.exchangeType != null) {
            this.exchangeType = other.exchangeType;
        }
        this.companyName = other.companyName;
        this.scripCode = other.scripCode;

        // Propagate imbalance triggers and last times
        this.vibTriggered = this.vibTriggered || other.vibTriggered;
        this.dibTriggered = this.dibTriggered || other.dibTriggered;
        this.trbTriggered = this.trbTriggered || other.trbTriggered;
        this.vrbTriggered = this.vrbTriggered || other.vrbTriggered;
        this.lastVibTriggerTime = Math.max(this.lastVibTriggerTime, other.lastVibTriggerTime);
        this.lastDibTriggerTime = Math.max(this.lastDibTriggerTime, other.lastDibTriggerTime);
        this.lastTrbTriggerTime = Math.max(this.lastTrbTriggerTime, other.lastTrbTriggerTime);
        this.lastVrbTriggerTime = Math.max(this.lastVrbTriggerTime, other.lastVrbTriggerTime);

        // Merge VPIN buckets: append and truncate to last 50; recalc VPIN
        if (other.vpinBuckets != null && !other.vpinBuckets.isEmpty()) {
            this.vpinBuckets.addAll(other.vpinBuckets);
            if (this.vpinBuckets.size() > VPIN_MAX_BUCKETS) {
                int excess = this.vpinBuckets.size() - VPIN_MAX_BUCKETS;
                for (int i = 0; i < excess; i++) this.vpinBuckets.remove(0);
            }
            calculateVPIN();
        }

        // Merge current VPIN bucket state and price tick count
        this.vpinCurrentBucketVolume += other.vpinCurrentBucketVolume;
        this.vpinCurrentBucketBuyVolume += other.vpinCurrentBucketBuyVolume;
        this.priceTickCount += other.priceTickCount;

        // BUG-007 FIX: Merge EWMA stats (weighted average by observation count)
        if (this.tickCount + other.tickCount > 0) {
            double w1 = (double) this.tickCount / (this.tickCount + other.tickCount);
            double w2 = (double) other.tickCount / (this.tickCount + other.tickCount);

            this.vibEwmaMean = w1 * this.vibEwmaMean + w2 * other.vibEwmaMean;
            this.vibEwmaSq = w1 * this.vibEwmaSq + w2 * other.vibEwmaSq;
            this.dibEwmaMean = w1 * this.dibEwmaMean + w2 * other.dibEwmaMean;
            this.dibEwmaSq = w1 * this.dibEwmaSq + w2 * other.dibEwmaSq;
            this.trbEwmaMean = w1 * this.trbEwmaMean + w2 * other.trbEwmaMean;
            this.trbEwmaSq = w1 * this.trbEwmaSq + w2 * other.trbEwmaSq;
            this.vrbEwmaMean = w1 * this.vrbEwmaMean + w2 * other.vrbEwmaMean;
            this.vrbEwmaSq = w1 * this.vrbEwmaSq + w2 * other.vrbEwmaSq;
        }

        // Normalize: flush any full VPIN buckets from accumulated current volume
        flushFullVPINBucketsFromCurrent();
    }

    /**
     * Convert any full VPIN buckets from the accumulated current-bucket volume into
     * finalized buckets, preserving the buy/sell ratio, so multi-minute rollups don't
     * lose bucketization due to 1m resets.
     */
    private void flushFullVPINBucketsFromCurrent() {
        if (vpinCurrentBucketVolume <= 0 || vpinBucketSize <= 0) return;

        double ratioBuy = vpinCurrentBucketVolume > 0 ? (vpinCurrentBucketBuyVolume / vpinCurrentBucketVolume) : 0.0;

        while (vpinCurrentBucketVolume >= vpinBucketSize) {
            double bucketVol = vpinBucketSize;
            double buyVol = ratioBuy * bucketVol;
            double sellVol = bucketVol - buyVol;
            vpinBuckets.add(new VPINBucket(bucketVol, buyVol, sellVol));

            // Truncate to last N buckets
            if (vpinBuckets.size() > VPIN_MAX_BUCKETS) {
                int excess = vpinBuckets.size() - VPIN_MAX_BUCKETS;
                for (int i = 0; i < excess; i++) vpinBuckets.remove(0);
            }

            // Remove from current bucket proportionally
            vpinCurrentBucketVolume -= bucketVol;
            vpinCurrentBucketBuyVolume -= buyVol;
        }

        // Recalculate VPIN from finalized buckets
        calculateVPIN();
    }

    /**
     * Get volume delta (buyVolume - sellVolume)
     */
    public long getVolumeDelta() {
        return buyVolume - sellVolume;
    }

    /**
     * Get volume delta percentage
     */
    public double getVolumeDeltaPercent() {
        return volume > 0 ? ((double)(buyVolume - sellVolume) / volume) * 100.0 : 0.0;
    }

    /**
     * Calculate Point of Control (POC) - price with maximum volume
     */
    public Double getPOC() {
        if (volumeAtPrice.isEmpty()) return null;
        
        return volumeAtPrice.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    /**
     * Calculate Value Area (70% of volume around POC)
     * Fixed algorithm: Properly expands from POC by alternating between higher and lower price levels
     */
    public ValueArea getValueArea() {
        if (volumeAtPrice.isEmpty() || volume == 0) {
            return new ValueArea(null, null, 0L);
        }

        Double poc = getPOC();
        if (poc == null) return new ValueArea(null, null, 0L);

        long targetVolume = (long) (volume * VALUE_AREA_PERCENTAGE);
        
        // Start with POC volume
        long accumulatedVolume = volumeAtPrice.getOrDefault(poc, 0L);

        double vaHigh = poc;
        double vaLow = poc;

        // Sort all prices
        java.util.List<Double> sortedPrices = new java.util.ArrayList<>(volumeAtPrice.keySet());
        java.util.Collections.sort(sortedPrices);

        // Find POC index in sorted list
        int pocIndex = -1;
        for (int i = 0; i < sortedPrices.size(); i++) {
            if (Math.abs(sortedPrices.get(i) - poc) < 0.0001) {
                pocIndex = i;
                break;
            }
        }

        if (pocIndex == -1) {
            // Shouldn't happen, but fallback
            return new ValueArea(poc, poc, accumulatedVolume);
        }

        // Expand value area by alternating between price levels above and below POC
        int aboveIndex = pocIndex + 1;
        int belowIndex = pocIndex - 1;

        while (accumulatedVolume < targetVolume && (aboveIndex < sortedPrices.size() || belowIndex >= 0)) {
            long aboveVolume = (aboveIndex < sortedPrices.size()) ? 
                volumeAtPrice.getOrDefault(sortedPrices.get(aboveIndex), 0L) : 0L;
            long belowVolume = (belowIndex >= 0) ? 
                volumeAtPrice.getOrDefault(sortedPrices.get(belowIndex), 0L) : 0L;

            // Add the price level with higher volume first (standard Volume Profile algorithm)
            if (aboveVolume >= belowVolume && aboveIndex < sortedPrices.size()) {
                accumulatedVolume += aboveVolume;
                vaHigh = sortedPrices.get(aboveIndex);
                aboveIndex++;
            } else if (belowIndex >= 0) {
                accumulatedVolume += belowVolume;
                vaLow = sortedPrices.get(belowIndex);
                belowIndex--;
            } else {
                // Only one direction left
                break;
            }
        }

        return new ValueArea(vaHigh, vaLow, accumulatedVolume);
    }

    /**
     * Value Area result holder
     */
    public static class ValueArea {
        public final Double high;
        public final Double low;
        public final Long volume;

        public ValueArea(Double high, Double low, Long volume) {
            this.high = high;
            this.low = low;
            this.volume = volume;
        }
    }

    /**
     * Update VPIN calculation with new trade
     * VPIN Algorithm (Easley, López de Prado, O'Hara 2012):
     * 1. Accumulate volume into buckets
     * 2. Track buy/sell volume per bucket
     * 3. VPIN = average of |buy - sell| / total over last 50 buckets
     */
    private void updateVPIN(int deltaVolume, boolean isBuy) {
        if (deltaVolume <= 0) return;

        // Add volume to current bucket
        vpinCurrentBucketVolume += deltaVolume;
        if (isBuy) {
            vpinCurrentBucketBuyVolume += deltaVolume;
        }

        // Check if bucket is full
        if (vpinCurrentBucketVolume >= vpinBucketSize) {
            // Create new bucket
            double sellVolume = vpinCurrentBucketVolume - vpinCurrentBucketBuyVolume;
            VPINBucket bucket = new VPINBucket(
                vpinCurrentBucketVolume,
                vpinCurrentBucketBuyVolume,
                sellVolume
            );

            // Add to bucket list
            vpinBuckets.add(bucket);

            // Keep only last VPIN_MAX_BUCKETS (50) buckets
            if (vpinBuckets.size() > VPIN_MAX_BUCKETS) {
                vpinBuckets.remove(0);
            }

            // Reset current bucket
            vpinCurrentBucketVolume = 0.0;
            vpinCurrentBucketBuyVolume = 0.0;

            // Recalculate VPIN
            calculateVPIN();

            // Adaptive bucket sizing (EWMA of actual bucket volumes)
            vpinBucketSize = VPIN_ADAPTIVE_ALPHA * bucket.totalVolume
                           + (1 - VPIN_ADAPTIVE_ALPHA) * vpinBucketSize;
        }
    }

    /**
     * Calculate VPIN as average of order flow imbalance over buckets
     */
    private void calculateVPIN() {
        if (vpinBuckets.isEmpty()) {
            vpin = 0.0;
            return;
        }

        double sumImbalance = 0.0;
        double sumVolume = 0.0;

        for (VPINBucket bucket : vpinBuckets) {
            sumImbalance += Math.abs(bucket.buyVolume - bucket.sellVolume);
            sumVolume += bucket.totalVolume;
        }

        // VPIN = average imbalance ratio
        vpin = sumVolume > 0 ? sumImbalance / sumVolume : 0.0;
    }

    /**
     * Get VPIN value
     */
    public double getVpin() {
        return vpin;
    }

    /**
     * Get number of VPIN buckets accumulated
     */
    public int getVpinBucketCount() {
        return vpinBuckets.size();
    }

    /**
     * Get current VPIN bucket size
     */
    public double getVpinBucketSize() {
        return vpinBucketSize;
    }

    /**
     * Get count of price updates (including price-only ticks)
     */
    public int getPriceTickCount() { return priceTickCount; }

    /**
     * VPIN Bucket data structure
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VPINBucket {
        public double totalVolume;
        public double buyVolume;
        public double sellVolume;

        public VPINBucket() {}

        @com.fasterxml.jackson.annotation.JsonCreator
        public VPINBucket(
                @com.fasterxml.jackson.annotation.JsonProperty("totalVolume") double totalVolume,
                @com.fasterxml.jackson.annotation.JsonProperty("buyVolume") double buyVolume,
                @com.fasterxml.jackson.annotation.JsonProperty("sellVolume") double sellVolume) {
            this.totalVolume = totalVolume;
            this.buyVolume = buyVolume;
            this.sellVolume = sellVolume;
        }

        @JsonIgnore
        public double getImbalance() {
            return totalVolume > 0 ? Math.abs(buyVolume - sellVolume) / totalVolume : 0.0;
        }
    }

    /**
     * Updates the human-readable timestamps based on windowStartMillis and windowEndMillis
     * BUG-017 FIX: Don't truncate seconds - use actual timestamp
     */
    public void updateHumanReadableTimestamps() {
        if (windowStartMillis > 0) {
            ZonedDateTime startTime = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(windowStartMillis),
                    ZoneId.of("Asia/Kolkata")
            );
            // BUG-017 FIX: Don't truncate seconds
            this.humanReadableStartTime = startTime.format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            );
        }

        if (windowEndMillis > 0) {
            ZonedDateTime endTime = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(windowEndMillis),
                    ZoneId.of("Asia/Kolkata")
            );
            // BUG-017 FIX: Don't truncate seconds
            this.humanReadableEndTime = endTime.format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            );
        }
    }

    /**
     * Sets the window start time in milliseconds and updates the human-readable representation.
     */
    public void setWindowStartMillis(long windowStartMillis) {
        this.windowStartMillis = windowStartMillis;
        updateHumanReadableTimestamps();
    }

    /**
     * Sets the window end time in milliseconds and updates the human-readable representation.
     */
    public void setWindowEndMillis(long windowEndMillis) {
        this.windowEndMillis = windowEndMillis;
        updateHumanReadableTimestamps();
    }

    /**
     * Provides a Kafka Serde for EnrichedCandlestick.
     */
    public static Serde<EnrichedCandlestick> serde() {
        return Serdes.serdeFrom(new EnrichedCandlestickSerializer(), new EnrichedCandlestickDeserializer());
    }

    // ---------------------------------------------------
    // Internal Serializer/Deserializer
    // ---------------------------------------------------
    // BUG-015 FIX: Shared, thread-safe ObjectMapper
    private static final ObjectMapper SHARED_OBJECT_MAPPER = new ObjectMapper()
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

    public static class EnrichedCandlestickSerializer implements Serializer<EnrichedCandlestick> {
        @Override
        public byte[] serialize(String topic, EnrichedCandlestick data) {
            if (data == null) return null;
            try {
                return SHARED_OBJECT_MAPPER.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed for EnrichedCandlestick", e);
            }
        }
    }

    public static class EnrichedCandlestickDeserializer implements Deserializer<EnrichedCandlestick> {
        @Override
        public EnrichedCandlestick deserialize(String topic, byte[] bytes) {
            if (bytes == null) return null;
            try {
                return SHARED_OBJECT_MAPPER.readValue(bytes, EnrichedCandlestick.class);
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed for EnrichedCandlestick", e);
            }
        }
    }
}
