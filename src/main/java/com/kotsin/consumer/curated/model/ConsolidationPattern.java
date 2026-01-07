package com.kotsin.consumer.curated.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ConsolidationPattern - Detected price compression pattern
 * Indicates Lower Highs + Higher Lows (coiling before breakout)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsolidationPattern {

    private String scripCode;
    private String timeframe;
    private double recentHigh;      // Most recent swing high
    private double recentLow;       // Most recent swing low
    private double compressionRatio; // (high - low) / ATR
    private boolean isCoiling;      // compressionRatio < 1.5 (very tight)
    private long detectedAt;

    // Additional context
    private double atr;             // ATR value used for calculation
    private int barsInConsolidation; // How many bars in this pattern

    /**
     * Check if consolidation is still valid (not too old)
     */
    public boolean isValid(long currentTime, long maxAgeMillis) {
        return (currentTime - detectedAt) <= maxAgeMillis;
    }

    /**
     * Get midpoint of consolidation range
     */
    public double getMidpoint() {
        return (recentHigh + recentLow) / 2.0;
    }

    /**
     * Get range width
     */
    public double getRange() {
        return recentHigh - recentLow;
    }
}
