package com.kotsin.consumer.smc.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * OrderBlock - Smart Money Concept order block detection.
 *
 * An Order Block is the last bearish candle before a bullish move (bullish OB)
 * or the last bullish candle before a bearish move (bearish OB).
 *
 * Represents institutional accumulation/distribution zones.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderBlock {

    private String symbol;
    private Instant timestamp;
    private String timeframe;

    // ==================== ORDER BLOCK ZONE ====================
    private OrderBlockType type;
    private double high;          // OB zone high
    private double low;           // OB zone low
    private double midpoint;      // (high + low) / 2
    private double open;          // OB candle open
    private double close;         // OB candle close

    // ==================== VALIDATION ====================
    private boolean isValid;      // OB still valid (not broken)
    private boolean isMitigated;  // Price returned to OB
    private int mitigationCount;  // Times price tested OB
    private Instant createdAt;
    private Instant mitigatedAt;

    // ==================== STRENGTH ====================
    private double moveSize;      // Size of move after OB
    private double movePercent;   // Move as percentage
    private int candlesToBreak;   // Candles before structure break
    private double volume;        // Volume at OB
    private boolean hasImbalance; // FVG exists near OB
    private OrderBlockStrength strength;

    // ==================== CONTEXT ====================
    private double structureBreakLevel;  // Level that was broken
    private TrendContext trendContext;
    private boolean isInDemandZone;      // Multiple bullish OBs
    private boolean isInSupplyZone;      // Multiple bearish OBs

    // ==================== ENUMS ====================

    public enum OrderBlockType {
        BULLISH,    // Last bearish candle before bullish move (demand)
        BEARISH     // Last bullish candle before bearish move (supply)
    }

    public enum OrderBlockStrength {
        STRONG,     // Large move, high volume, with imbalance
        MODERATE,   // Decent move, normal volume
        WEAK        // Small move, low volume
    }

    public enum TrendContext {
        WITH_TREND,     // OB in direction of larger trend
        COUNTER_TREND,  // OB against larger trend
        RANGING         // No clear trend
    }

    // ==================== HELPER METHODS ====================

    public boolean isBullish() {
        return type == OrderBlockType.BULLISH;
    }

    public boolean isBearish() {
        return type == OrderBlockType.BEARISH;
    }

    public boolean isStrong() {
        return strength == OrderBlockStrength.STRONG;
    }

    public double getZoneSize() {
        return high - low;
    }

    public double getZoneSizePercent() {
        return midpoint > 0 ? (high - low) / midpoint * 100 : 0;
    }

    public boolean isPriceInZone(double price) {
        return price >= low && price <= high;
    }

    public boolean isPriceAboveZone(double price) {
        return price > high;
    }

    public boolean isPriceBelowZone(double price) {
        return price < low;
    }

    public double getDistanceFromZone(double price) {
        if (isPriceInZone(price)) return 0;
        return price > high ? price - high : low - price;
    }

    public double getDistancePercent(double price) {
        return midpoint > 0 ? getDistanceFromZone(price) / midpoint * 100 : 0;
    }

    /**
     * Check if price is near the zone (within tolerance percent).
     */
    public boolean isPriceNearZone(double price, double tolerancePercent) {
        return getDistancePercent(price) <= tolerancePercent;
    }
}
