package com.kotsin.consumer.curated.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FuturesData - Futures contract data for a scrip
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FuturesData {

    private String scripCode;
    private String symbol;              // e.g., "RELIANCE25JANFUT"

    // Price data
    private double futuresPrice;        // Current futures price
    private double spotPrice;           // Spot (equity) price
    private double premium;             // (futures - spot) / spot * 100 (percentage)
    private boolean premiumPositive;    // true if futures > spot (bullish)

    // Open Interest
    private long openInterest;
    private long prevOpenInterest;
    private long oiChange;              // OI change (current - previous)
    private double oiChangePercent;     // OI change percentage

    // Volume
    private long volume;
    private double volumeChangePercent;

    // Price change
    private double priceChange;
    private double priceChangePercent;

    // Buildup type
    private BuildupType buildup;

    // Data freshness
    private long timestamp;
    private boolean isStale;            // true if data is > 5 min old

    /**
     * Buildup classification based on price + OI movement
     */
    public enum BuildupType {
        LONG_BUILDUP,      // Price ↑ + OI ↑ (Fresh longs, BULLISH)
        SHORT_BUILDUP,     // Price ↓ + OI ↑ (Fresh shorts, BEARISH)
        LONG_UNWINDING,    // Price ↓ + OI ↓ (Longs exiting, BEARISH)
        SHORT_COVERING,    // Price ↑ + OI ↓ (Shorts covering, BULLISH)
        NEUTRAL            // No clear pattern
    }

    /**
     * Detect buildup type from price and OI changes
     */
    public static BuildupType detectBuildup(double priceChange, double oiChange) {
        if (priceChange > 0 && oiChange > 0) {
            return BuildupType.LONG_BUILDUP;
        } else if (priceChange < 0 && oiChange > 0) {
            return BuildupType.SHORT_BUILDUP;
        } else if (priceChange < 0 && oiChange < 0) {
            return BuildupType.LONG_UNWINDING;
        } else if (priceChange > 0 && oiChange < 0) {
            return BuildupType.SHORT_COVERING;
        }
        return BuildupType.NEUTRAL;
    }

    /**
     * Check if data is fresh (< 5 minutes old)
     */
    public boolean isFresh() {
        if (timestamp == 0) return false;
        long ageMillis = System.currentTimeMillis() - timestamp;
        return ageMillis < (5 * 60 * 1000);  // 5 minutes
    }

    /**
     * Check if futures indicate bullish sentiment
     */
    public boolean isBullish() {
        return (buildup == BuildupType.LONG_BUILDUP || buildup == BuildupType.SHORT_COVERING)
                && premiumPositive;
    }
}
