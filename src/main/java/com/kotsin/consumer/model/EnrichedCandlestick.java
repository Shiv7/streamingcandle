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
 * Features:
 * - OHLCV (basic candle data)
 * - Buy/Sell Volume separation
 * - VWAP calculation
 * - Imbalance Bars (VIB, DIB, TRB, VRB with EWMA thresholds)
 * - Volume Profile (POC, Value Area)
 */
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
    private static final double EWMA_ALPHA = 0.1;

    // ========== Volume Profile State ==========
    private Map<Double, Long> volumeAtPrice = new HashMap<>();
    private Double lowestPrice;
    private Double highestPrice;
    private static final double DEFAULT_TICK_SIZE = 0.05;
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
    @JsonIgnore
    private transient Double lastPrice; // kept transient (only for classification heuristics)
    private boolean openInitialized = false;
    private boolean highInitialized = false;
    private boolean lowInitialized = false;
    private long openSourceTs = Long.MAX_VALUE;
    private long closeSourceTs = Long.MIN_VALUE;
    private int priceTickCount = 0;

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
    }

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
            if (lastPrice == null || Math.abs(px - lastPrice) >= getClassificationThreshold(px)) {
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

        double threshold = getClassificationThreshold(currentPrice);

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

    private double getClassificationThreshold(double currentPrice) {
        // Threshold: 1bp or 0.01 minimum; options can have wider spread. Keep simple for now.
        return Math.max(0.01, Math.abs(currentPrice) * 0.0001);
    }

    /**
     * Update imbalance bars (VIB, DIB, TRB, VRB) with EWMA thresholds
     */
    private void updateImbalanceBars(TickData tick, double price, int deltaVolume, boolean isBuy, long eventTime) {
        String direction = isBuy ? "BUY" : "SELL";
        int directionSign = isBuy ? 1 : -1;

        // Volume Imbalance (VIB)
        long signedVolume = deltaVolume * directionSign;
        volumeImbalance += signedVolume;

        // Dollar Imbalance (DIB)
        double dollarVolume = (double) deltaVolume * price;
        dollarImbalance += dollarVolume * directionSign;

        // Tick Runs (TRB)
        if (direction.equals(currentDirection)) {
            tickRuns++;
        } else {
            tickRuns = 1;
            currentDirection = direction;
        }

        // Volume Runs (VRB)
        if (direction.equals(currentDirection)) {
            volumeRuns += deltaVolume;
        } else {
            volumeRuns = deltaVolume;
        }

        // Check thresholds and update EWMA
        checkAndUpdateThresholds(eventTime);
    }

    /**
     * Check imbalance bar thresholds and update EWMA estimates
     * NOW WITH BAR EMISSION TRACKING!
     */
    private void checkAndUpdateThresholds(long eventTime) {
        long currentTime = eventTime > 0 ? eventTime : System.currentTimeMillis();

        // VIB threshold check
        if (Math.abs(volumeImbalance) >= expectedVolumeImbalance) {
            vibTriggered = true;  // NEW: Mark that VIB bar was triggered
            lastVibTriggerTime = currentTime;  // NEW: Track when
            expectedVolumeImbalance = EWMA_ALPHA * Math.abs(volumeImbalance)
                                    + (1 - EWMA_ALPHA) * expectedVolumeImbalance;
            volumeImbalance = 0L;  // Reset after bar emission
        }

        // DIB threshold check
        if (Math.abs(dollarImbalance) >= expectedDollarImbalance) {
            dibTriggered = true;  // NEW: Mark that DIB bar was triggered
            lastDibTriggerTime = currentTime;  // NEW: Track when
            expectedDollarImbalance = EWMA_ALPHA * Math.abs(dollarImbalance)
                                    + (1 - EWMA_ALPHA) * expectedDollarImbalance;
            dollarImbalance = 0L;
        }

        // TRB threshold check
        if (Math.abs(tickRuns) >= expectedTickRuns) {
            trbTriggered = true;  // NEW: Mark that TRB bar was triggered
            lastTrbTriggerTime = currentTime;  // NEW: Track when
            expectedTickRuns = EWMA_ALPHA * Math.abs(tickRuns)
                             + (1 - EWMA_ALPHA) * expectedTickRuns;
            tickRuns = 0;
        }

        // VRB threshold check
        if (Math.abs(volumeRuns) >= expectedVolumeRuns) {
            vrbTriggered = true;  // NEW: Mark that VRB bar was triggered
            lastVrbTriggerTime = currentTime;  // NEW: Track when
            expectedVolumeRuns = EWMA_ALPHA * Math.abs(volumeRuns)
                               + (1 - EWMA_ALPHA) * expectedVolumeRuns;
            volumeRuns = 0L;
        }
    }

    /**
     * Update volume profile (POC and Value Area calculation)
     */
    private void updateVolumeProfile(double price, long volume) {
        if (volume <= 0) return;

        // Round price to instrument tick size using BigDecimal to avoid float artifacts
        double tickSize = getTickSize();
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
     */
    private double getTickSize() {
        if ("D".equalsIgnoreCase(exchangeType) || "F".equalsIgnoreCase(exchangeType) || "O".equalsIgnoreCase(exchangeType)) {
            return 0.05; // common for derivatives/options
        }
        return DEFAULT_TICK_SIZE;
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

        long candidateOpenTs = otherStart > 0 ? otherStart : otherEnd;
        if (otherHasPrice && (!this.openInitialized
                || (candidateOpenTs > 0 && candidateOpenTs < this.openSourceTs))) {
            this.open = other.open;
            this.openInitialized = true;
            if (candidateOpenTs > 0) {
                this.openSourceTs = candidateOpenTs;
            }
        }

        long candidateCloseTs = otherEnd > 0 ? otherEnd : otherStart;
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

        other.volumeAtPrice.forEach((price, vol) ->
                this.volumeAtPrice.merge(price, vol, Long::sum));

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

        public double getImbalance() {
            return totalVolume > 0 ? Math.abs(buyVolume - sellVolume) / totalVolume : 0.0;
        }
    }

    /**
     * Updates the human-readable timestamps based on windowStartMillis and windowEndMillis
     */
    public void updateHumanReadableTimestamps() {
        if (windowStartMillis > 0) {
            ZonedDateTime startTime = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(windowStartMillis),
                    ZoneId.of("Asia/Kolkata")
            );
            startTime = startTime.withSecond(0).withNano(0);
            this.humanReadableStartTime = startTime.format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            );
        }

        if (windowEndMillis > 0) {
            ZonedDateTime endTime = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(windowEndMillis),
                    ZoneId.of("Asia/Kolkata")
            );
            endTime = endTime.withSecond(0).withNano(0);
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
    public static class EnrichedCandlestickSerializer implements Serializer<EnrichedCandlestick> {
        private final ObjectMapper objectMapper = new ObjectMapper();

        @Override
        public byte[] serialize(String topic, EnrichedCandlestick data) {
            if (data == null) return null;
            try {
                return objectMapper.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed for EnrichedCandlestick", e);
            }
        }
    }

    public static class EnrichedCandlestickDeserializer implements Deserializer<EnrichedCandlestick> {
        private final ObjectMapper objectMapper = new ObjectMapper();

        @Override
        public EnrichedCandlestick deserialize(String topic, byte[] bytes) {
            if (bytes == null) return null;
            try {
                return objectMapper.readValue(bytes, EnrichedCandlestick.class);
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed for EnrichedCandlestick", e);
            }
        }
    }
}
