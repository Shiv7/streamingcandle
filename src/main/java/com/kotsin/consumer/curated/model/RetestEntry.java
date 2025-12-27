package com.kotsin.consumer.curated.model;

import com.kotsin.consumer.model.UnifiedCandle;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RetestEntry - Precise entry point when price retests the breakout pivot
 * This is the actual trade entry signal
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetestEntry {

    private String scripCode;
    private long timestamp;

    // Entry levels
    private double entryPrice;      // Close of retest bar (or microprice)
    private double stopLoss;        // Below pivot - safety margin
    private double target;          // Measured move or nearest resistance
    private double riskReward;      // (target - entry) / (entry - stop)

    // Reference levels
    private double pivotLevel;      // Breakout level being retested
    private double microprice;      // Orderbook-derived fair price

    // Retest bar metrics
    private long retestBarVolume;
    private long retestBarVolumeDelta;
    private double retestBarOFI;
    private boolean buyingPressure; // volumeDelta > 0 && OFI > 0

    // Position sizing hint
    private double positionSizeMultiplier; // Based on quality (0.5 to 1.5)

    /**
     * Check if this is a valid entry (good R:R and buying pressure)
     */
    public boolean isValidEntry() {
        return riskReward >= 1.5
                && buyingPressure
                && entryPrice > stopLoss
                && target > entryPrice;
    }

    /**
     * Calculate risk amount (entry - stop)
     */
    public double getRiskAmount() {
        return entryPrice - stopLoss;
    }

    /**
     * Calculate potential reward (target - entry)
     */
    public double getRewardAmount() {
        return target - entryPrice;
    }
}
