package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * TickCandle - Lean 1-minute candle from tick aggregation.
 *
 * Contains ONLY tick-derived data (~35 fields vs 290 in old InstrumentCandle):
 * - OHLCV (price, volume)
 * - Trade classification (buy/sell pressure)
 * - Volume profile (POC, VAH, VAL)
 * - VPIN (informed trading probability)
 * - Imbalance indicators
 *
 * Does NOT contain (separate collections):
 * - Orderbook metrics → OrderbookMetrics
 * - OI metrics → OIMetrics
 * - Greeks → computed on-demand
 *
 * Benefits:
 * - 80% smaller serialization
 * - Single responsibility
 * - Fast MongoDB queries
 * - Clear data lineage
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Document(collection = "tick_candles_1m")
@CompoundIndexes({
    @CompoundIndex(name = "symbol_timestamp_idx", def = "{'symbol': 1, 'timestamp': -1}"),
    @CompoundIndex(name = "scripCode_timestamp_idx", def = "{'scripCode': 1, 'timestamp': -1}"),
    // Bug #6 FIX: Include exchange in unique index to avoid MCX/NSE scripCode collisions
    @CompoundIndex(name = "exchange_scripCode_windowStart_unique_idx",
                   def = "{'exchange': 1, 'scripCode': 1, 'windowStart': 1}",
                   unique = true)
})
public class TickCandle {

    @Id
    private String id;

    // ==================== IDENTITY ====================
    private String symbol;           // "NIFTY", "RELIANCE", etc.
    private String scripCode;        // Exchange scrip code
    private String exchange;         // "N" (NSE), "B" (BSE), "M" (MCX)
    private String exchangeType;     // "C" (Cash), "D" (Derivative)
    private String companyName;      // Full company name from TickData
    private InstrumentType instrumentType;

    // ==================== TIMING ====================
    @Indexed(expireAfter = "90d")    // TTL: 90 days auto-cleanup
    private Instant timestamp;        // Window end time (canonical)
    private Instant windowStart;
    private Instant windowEnd;

    // ==================== OHLCV (Core) ====================
    private double open;
    private double high;
    private double low;
    private double close;
    private long volume;
    private double value;            // volume * vwap (turnover)

    // ==================== VWAP ====================
    private double vwap;
    private double typicalPrice;     // (H+L+C)/3

    // ==================== TRADE CLASSIFICATION ====================
    private long buyVolume;          // Aggressive buys (lifted offers)
    private long sellVolume;         // Aggressive sells (hit bids)
    private long midpointVolume;     // Trades at midpoint (iceberg indicator)
    private long volumeDelta;        // buyVolume - sellVolume
    private double buyPressure;      // buyVolume / totalVolume
    private double sellPressure;     // sellVolume / totalVolume

    // ==================== VPIN ====================
    private double vpin;             // Volume-Synchronized Probability of Informed Trading (0-1)
    private double vpinBucketSize;   // Adaptive bucket size used

    // ==================== VOLUME PROFILE ====================
    private double poc;              // Point of Control (price with max volume)
    private double vah;              // Value Area High (70% volume upper bound)
    private double val;              // Value Area Low (70% volume lower bound)
    private Map<String, Long> volumeAtPrice;  // Sparse map: price (as paise string) → volume

    // ==================== IMBALANCE BARS ====================
    private double volumeImbalance;   // Cumulative signed volume
    private double dollarImbalance;   // Cumulative signed dollar volume
    private int tickRuns;             // Consecutive same-direction ticks
    private boolean vibTriggered;     // Volume Imbalance Bar triggered
    private boolean dibTriggered;     // Dollar Imbalance Bar triggered
    private boolean trbTriggered;     // Tick Run Bar triggered

    // ==================== TICK INTENSITY ====================
    private int tickCount;            // Total ticks in window
    private double ticksPerSecond;    // Activity level
    private int largeTradeCount;      // Trades > 10x average (block trades)

    // ==================== QUALITY ====================
    private String quality;           // VALID, WARNING, STALE
    private long processingLatencyMs; // Time from tick to candle emission

    // ==================== OPTIONS METADATA (if applicable) ====================
    private Double strikePrice;       // For options only
    private String optionType;        // "CE" or "PE"
    private String expiry;            // "2026-01-30"
    private Integer daysToExpiry;

    // ==================== METADATA ====================
    private Instant createdAt;

    // ==================== HELPER METHODS ====================

    /**
     * Check if this is an option candle.
     */
    public boolean isOption() {
        return instrumentType == InstrumentType.OPTION_CE ||
               instrumentType == InstrumentType.OPTION_PE;
    }

    /**
     * Check if this is a derivative (future or option).
     */
    public boolean isDerivative() {
        return instrumentType == InstrumentType.FUTURE || isOption();
    }

    /**
     * Get the range (high - low).
     */
    public double getRange() {
        return high - low;
    }

    /**
     * Get body size (|close - open|).
     */
    public double getBodySize() {
        return Math.abs(close - open);
    }

    /**
     * Check if bullish candle.
     */
    public boolean isBullish() {
        return close > open;
    }

    /**
     * Check if bearish candle.
     */
    public boolean isBearish() {
        return close < open;
    }

    /**
     * Get upper wick size.
     */
    public double getUpperWick() {
        return high - Math.max(open, close);
    }

    /**
     * Get lower wick size.
     */
    public double getLowerWick() {
        return Math.min(open, close) - low;
    }

    /**
     * Instrument types supported.
     */
    public enum InstrumentType {
        INDEX,
        EQUITY,
        FUTURE,
        OPTION_CE,
        OPTION_PE;

        public boolean isOption() {
            return this == OPTION_CE || this == OPTION_PE;
        }

        public boolean isDerivative() {
            return this == FUTURE || isOption();
        }

        public static InstrumentType detect(String exchange, String exchangeType, String companyName) {
            if (companyName == null) return EQUITY;

            String upper = companyName.toUpperCase();
            if (upper.contains(" CE ")) return OPTION_CE;
            if (upper.contains(" PE ")) return OPTION_PE;
            if (upper.contains("FUT") || "D".equals(exchangeType)) {
                if (upper.contains(" CE ") || upper.contains(" PE ")) {
                    return upper.contains(" CE ") ? OPTION_CE : OPTION_PE;
                }
                return FUTURE;
            }
            if (upper.contains("NIFTY") && !upper.contains(" ")) return INDEX;
            return EQUITY;
        }
    }
}
