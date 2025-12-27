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

    // ==================== VPIN (Adaptive) ====================
    private double vpin;
    private double vpinBucketSize;   // Adaptive based on daily volume
    private int vpinBucketCount;

    // ==================== ORDERBOOK METRICS (Optional - not for INDEX) ====================
    private boolean orderbookPresent;
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

    // ==================== OI METRICS (Optional - only for derivatives) ====================
    private boolean oiPresent;
    private Long openInterest;
    private Long oiOpen;
    private Long oiHigh;
    private Long oiLow;
    private Long oiClose;
    private Long oiChange;
    private Double oiChangePercent;

    // ==================== OPTIONS SPECIFIC (only for OPTION_CE/PE) ====================
    private Double strikePrice;      // Strike price for options
    private String optionType;       // "CE" or "PE"
    private String expiry;           // Expiry date string

    // ==================== DATA QUALITY ====================
    private DataQuality quality;
    private String qualityReason;

    // ==================== LATENCY TRACKING ====================
    private long processingLatencyMs;
    private long maxTickAgeMs;
    private long minTickAgeMs;

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
