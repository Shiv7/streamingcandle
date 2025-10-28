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
    private long dollarImbalance;
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
    private static final double PRICE_TICK_SIZE = 0.05;
    private static final double VALUE_AREA_PERCENTAGE = 0.70;

    // ========== Transient Processing Fields ==========
    @JsonIgnore
    private transient long firstTs = Long.MAX_VALUE;
    @JsonIgnore
    private transient long lastTs = Long.MIN_VALUE;
    @JsonIgnore
    private transient Double lastPrice;

    /**
     * Creates a new empty enriched candlestick with default values.
     */
    public EnrichedCandlestick() {
        this.open = 0;
        this.high = Double.MIN_VALUE;
        this.low = Double.MAX_VALUE;
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
        long ts = tick.getTimestamp();
        double px = tick.getLastRate();

        // ========== OHLC by event time ==========
        if (ts < firstTs) {
            firstTs = ts;
            open = px;
        }
        if (ts >= lastTs) {
            lastTs = ts;
            close = px;
        }

        high = (high == Double.MIN_VALUE) ? px : Math.max(high, px);
        low = (low == Double.MAX_VALUE) ? px : Math.min(low, px);

        // ========== Volume (delta, buy/sell separation) ==========
        Integer dv = tick.getDeltaVolume();
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
            updateImbalanceBars(tick, px, dv, isBuy);

            // ========== Volume Profile Update ==========
            updateVolumeProfile(px, dv);
            
            // ========== Calculate VWAP ==========
            vwap = volume > 0 ? priceVolumeSum / volume : 0.0;
        }

        // ========== Metadata (idempotent) ==========
        if (companyName == null) companyName = tick.getCompanyName();
        if (scripCode == null) scripCode = tick.getScripCode();
        exchange = tick.getExchange();
        if (exchangeType == null) exchangeType = tick.getExchangeType();

        lastPrice = px;
    }

    /**
     * Classify trade as buy or sell using quote-rule then tick-rule
     */
    private boolean classifyTrade(TickData tick, double currentPrice) {
        double bidPrice = tick.getBidRate();
        double askPrice = tick.getOfferRate();

        // Threshold: 1bp of price or 0.01 minimum
        double threshold = Math.max(0.01, Math.abs(currentPrice) * 0.0001);

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

    /**
     * Update imbalance bars (VIB, DIB, TRB, VRB) with EWMA thresholds
     */
    private void updateImbalanceBars(TickData tick, double price, int deltaVolume, boolean isBuy) {
        String direction = isBuy ? "BUY" : "SELL";
        int directionSign = isBuy ? 1 : -1;

        // Volume Imbalance (VIB)
        long signedVolume = deltaVolume * directionSign;
        volumeImbalance += signedVolume;

        // Dollar Imbalance (DIB)
        long dollarVolume = (long)(deltaVolume * price);
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
        checkAndUpdateThresholds();
    }

    /**
     * Check imbalance bar thresholds and update EWMA estimates
     */
    private void checkAndUpdateThresholds() {
        // VIB threshold check
        if (Math.abs(volumeImbalance) >= expectedVolumeImbalance) {
            expectedVolumeImbalance = EWMA_ALPHA * Math.abs(volumeImbalance)
                                    + (1 - EWMA_ALPHA) * expectedVolumeImbalance;
            volumeImbalance = 0L;  // Reset after bar emission
        }

        // DIB threshold check
        if (Math.abs(dollarImbalance) >= expectedDollarImbalance) {
            expectedDollarImbalance = EWMA_ALPHA * Math.abs(dollarImbalance)
                                    + (1 - EWMA_ALPHA) * expectedDollarImbalance;
            dollarImbalance = 0L;
        }

        // TRB threshold check
        if (Math.abs(tickRuns) >= expectedTickRuns) {
            expectedTickRuns = EWMA_ALPHA * Math.abs(tickRuns)
                             + (1 - EWMA_ALPHA) * expectedTickRuns;
            tickRuns = 0;
        }

        // VRB threshold check
        if (Math.abs(volumeRuns) >= expectedVolumeRuns) {
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

        // Round price to tick size
        double rounded = Math.round(price / PRICE_TICK_SIZE) * PRICE_TICK_SIZE;

        volumeAtPrice.merge(rounded, volume, Long::sum);

        // Update price range
        if (lowestPrice == null || rounded < lowestPrice) lowestPrice = rounded;
        if (highestPrice == null || rounded > highestPrice) highestPrice = rounded;
    }

    /**
     * Merges another EnrichedCandlestick into this one.
     * Used when building multi-minute candles from smaller timeframe candles.
     * 
     * CRITICAL: This aggregates pre-computed candles (2m from 1m, 5m from 1m, etc.)
     *
     * @param other The candle to merge into this one
     */
    public void updateCandle(EnrichedCandlestick other) {
        // Set open price only for the first candle in the window
        if (this.open == 0) {
            this.open = other.open;
        }

        // Take highest high and lowest low
        this.high = Math.max(this.high, other.high);
        this.low = Math.min(this.low, other.low);

        // Always update close to the latest candle's close
        this.close = other.close;

        // Accumulate volumes
        this.volume += other.volume;
        this.buyVolume += other.buyVolume;
        this.sellVolume += other.sellVolume;
        this.priceVolumeSum += other.priceVolumeSum;
        this.tickCount += other.tickCount;
        
        // Recalculate VWAP after merge
        this.vwap = this.volume > 0 ? this.priceVolumeSum / this.volume : 0.0;

        // Merge imbalance bars
        this.volumeImbalance += other.volumeImbalance;
        this.dollarImbalance += other.dollarImbalance;
        // Note: tickRuns and volumeRuns are per-window, not cumulative across windows

        // Merge volume profile
        other.volumeAtPrice.forEach((price, vol) -> 
            this.volumeAtPrice.merge(price, vol, Long::sum));
        
        if (other.lowestPrice != null) {
            this.lowestPrice = (this.lowestPrice == null) ? other.lowestPrice : Math.min(this.lowestPrice, other.lowestPrice);
        }
        if (other.highestPrice != null) {
            this.highestPrice = (this.highestPrice == null) ? other.highestPrice : Math.max(this.highestPrice, other.highestPrice);
        }

        // Update metadata
        this.exchange = other.exchange;
        if (other.exchangeType != null) {
            this.exchangeType = other.exchangeType;
        }
        this.companyName = other.companyName;
        this.scripCode = other.scripCode;
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
     */
    public ValueArea getValueArea() {
        if (volumeAtPrice.isEmpty() || volume == 0) {
            return new ValueArea(null, null, 0L);
        }

        Double poc = getPOC();
        if (poc == null) return new ValueArea(null, null, 0L);

        long targetVolume = (long) (volume * VALUE_AREA_PERCENTAGE);
        long accumulatedVolume = 0L;

        double vaHigh = poc;
        double vaLow = poc;

        // Sort by proximity to POC
        volumeAtPrice.entrySet().stream()
            .sorted((e1, e2) -> Double.compare(
                Math.abs(e1.getKey() - poc),
                Math.abs(e2.getKey() - poc)
            ))
            .forEach(entry -> {
                // This is inefficient but Java doesn't allow mutation in forEach easily
                // We'll refactor with a traditional loop if needed
            });

        // Traditional loop for value area calculation
        for (Map.Entry<Double, Long> entry : volumeAtPrice.entrySet()) {
            if (accumulatedVolume >= targetVolume) break;
            
            double price = entry.getKey();
            if (Math.abs(price - poc) <= Math.max(Math.abs(vaHigh - poc), Math.abs(vaLow - poc)) * 1.5) {
                accumulatedVolume += entry.getValue();
                if (price > vaHigh) vaHigh = price;
                if (price < vaLow) vaLow = price;
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

