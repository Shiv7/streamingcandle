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

/**
 * OIMetrics - Open Interest metrics for 1-minute window.
 *
 * Contains ONLY OI-derived data (~15 fields):
 * - Current OI
 * - OI change (intraday and daily)
 * - OI interpretation (buildup type)
 * - OI velocity
 *
 * Only applicable to derivatives (futures and options).
 *
 * OI Interpretation Logic (CRITICAL for institutional analysis):
 * - LONG_BUILDUP:    Price ↑ + OI ↑ → New longs entering (bullish continuation)
 * - SHORT_COVERING:  Price ↑ + OI ↓ → Shorts exiting (bullish, may exhaust)
 * - SHORT_BUILDUP:   Price ↓ + OI ↑ → New shorts entering (bearish continuation)
 * - LONG_UNWINDING:  Price ↓ + OI ↓ → Longs exiting (bearish, may exhaust)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Document(collection = "oi_metrics_1m")
@CompoundIndexes({
    @CompoundIndex(name = "symbol_timestamp_idx", def = "{'symbol': 1, 'timestamp': -1}"),
    @CompoundIndex(name = "scripCode_timestamp_idx", def = "{'scripCode': 1, 'timestamp': -1}"),
    // Bug #6 FIX: Include exchange in unique index to avoid MCX/NSE scripCode collisions
    @CompoundIndex(name = "exchange_scripCode_windowStart_unique_idx",
                   def = "{'exchange': 1, 'scripCode': 1, 'windowStart': 1}",
                   unique = true)
})
public class OIMetrics {

    @Id
    private String id;

    // ==================== IDENTITY ====================
    private String symbol;
    private String scripCode;
    private String exchange;
    private String exchangeType;

    // For options: link to underlying
    private String underlyingSymbol;
    private Double strikePrice;
    private String optionType;        // "CE" or "PE"
    private String expiry;

    // ==================== TIMING ====================
    @Indexed(expireAfter = "90d")
    private Instant timestamp;
    private Instant windowStart;
    private Instant windowEnd;

    // ==================== OI DATA ====================
    /**
     * Current Open Interest (number of outstanding contracts).
     */
    private long openInterest;

    /**
     * OI at start of current window.
     */
    private long oiOpen;

    /**
     * OI at end of current window (same as openInterest).
     */
    private long oiClose;

    /**
     * OI change within this window (oiClose - oiOpen).
     */
    private long oiChange;

    /**
     * OI change as percentage.
     */
    private double oiChangePercent;

    // ==================== DAILY REFERENCE ====================
    /**
     * Previous day's closing OI (for daily change calculation).
     */
    private long previousDayOI;

    /**
     * Daily OI change (current OI - previous day OI).
     */
    private long dailyOIChange;

    /**
     * Daily OI change as percentage.
     */
    private double dailyOIChangePercent;

    // ==================== OI INTERPRETATION ====================
    /**
     * OI interpretation based on price and OI change.
     *
     * LONG_BUILDUP:    Price ↑ + OI ↑ → Bullish continuation
     * SHORT_COVERING:  Price ↑ + OI ↓ → Bullish (shorts exiting)
     * SHORT_BUILDUP:   Price ↓ + OI ↑ → Bearish continuation
     * LONG_UNWINDING:  Price ↓ + OI ↓ → Bearish (longs exiting)
     * NEUTRAL:         No significant change
     */
    private OIInterpretation interpretation;

    /**
     * Confidence in OI interpretation (0-1).
     * Higher when both price and OI changes are significant.
     */
    private double interpretationConfidence;

    /**
     * True if OI interpretation suggests potential reversal.
     * - SHORT_COVERING during downtrend → bullish reversal
     * - LONG_UNWINDING during uptrend → bearish reversal
     */
    private boolean suggestsReversal;

    // ==================== OI VELOCITY ====================
    /**
     * OI velocity = rate of OI change per minute.
     */
    private double oiVelocity;

    /**
     * OI acceleration = change in velocity.
     */
    private double oiAcceleration;

    // ==================== UPDATE STATS ====================
    private int updateCount;
    private Instant lastUpdateTimestamp;

    // ==================== QUALITY ====================
    private String quality;
    private long staleness;

    // ==================== METADATA ====================
    private Instant createdAt;

    // ==================== ENUMS ====================

    public enum OIInterpretation {
        LONG_BUILDUP,      // Price ↑ + OI ↑
        SHORT_COVERING,    // Price ↑ + OI ↓
        SHORT_BUILDUP,     // Price ↓ + OI ↑
        LONG_UNWINDING,    // Price ↓ + OI ↓
        NEUTRAL;           // No significant change

        /**
         * Determine interpretation from price and OI changes.
         *
         * @param priceChange    Price change (positive = up)
         * @param oiChange       OI change (positive = increase)
         * @param priceThreshold Minimum price change % to consider significant
         * @param oiThreshold    Minimum OI change % to consider significant
         */
        public static OIInterpretation determine(double priceChange, double oiChange,
                                                  double priceThreshold, double oiThreshold) {
            boolean priceUp = priceChange > priceThreshold;
            boolean priceDown = priceChange < -priceThreshold;
            boolean oiUp = oiChange > oiThreshold;
            boolean oiDown = oiChange < -oiThreshold;

            if (priceUp && oiUp) return LONG_BUILDUP;
            if (priceUp && oiDown) return SHORT_COVERING;
            if (priceDown && oiUp) return SHORT_BUILDUP;
            if (priceDown && oiDown) return LONG_UNWINDING;
            return NEUTRAL;
        }

        /**
         * Check if this interpretation is bullish.
         */
        public boolean isBullish() {
            return this == LONG_BUILDUP || this == SHORT_COVERING;
        }

        /**
         * Check if this interpretation is bearish.
         */
        public boolean isBearish() {
            return this == SHORT_BUILDUP || this == LONG_UNWINDING;
        }

        /**
         * Check if this interpretation suggests exhaustion (unwinding/covering).
         */
        public boolean suggestsExhaustion() {
            return this == SHORT_COVERING || this == LONG_UNWINDING;
        }

        /**
         * Check if this interpretation suggests continuation (buildup).
         */
        public boolean suggestsContinuation() {
            return this == LONG_BUILDUP || this == SHORT_BUILDUP;
        }
    }

    // ==================== HELPER METHODS ====================

    /**
     * Check if OI data is fresh.
     */
    public boolean isFresh() {
        return staleness < 30000;  // OI updates less frequently, 30s threshold
    }

    /**
     * Check if significant OI buildup.
     */
    public boolean hasSignificantBuildup() {
        return Math.abs(oiChangePercent) > 1.0;  // > 1% change
    }

    /**
     * Get OI signal strength (0-1) based on change magnitude.
     */
    public double getSignalStrength() {
        // Normalize: 5% change = full strength
        return Math.min(1.0, Math.abs(oiChangePercent) / 5.0);
    }
}
