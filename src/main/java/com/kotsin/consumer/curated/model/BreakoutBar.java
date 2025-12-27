package com.kotsin.consumer.curated.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * BreakoutBar - The specific candle that breaks out of consolidation
 * Contains all metrics that confirm the breakout quality
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BreakoutBar {

    private String scripCode;
    private String timeframe;
    private long timestamp;

    // Breakout bar OHLC
    private double breakoutPrice;   // Close price
    private double breakoutHigh;
    private double breakoutLow;
    private double breakoutOpen;

    // Volume metrics
    private long volume;
    private double volumeZScore;    // How abnormal is volume (standard deviations from mean)
    private double avgVolume20;     // 20-period average for reference

    // Liquidity metrics
    private double kyleLambda;      // Price impact coefficient (high = institutional absorption)

    // Microstructure confirmations
    private double ofi;             // Order Flow Imbalance
    private double vpin;            // Volume-synchronized probability
    private long volumeDelta;       // Buy volume - Sell volume

    // Structure metrics
    private double pivotLevel;      // Old resistance becomes new support
    private double compressionRatio; // How tight was the consolidation

    // Entry/Stop levels (calculated later during retest)
    private Double retestEntry;     // Entry on retest at pivot
    private Double stopLoss;        // Below pivot - 1 ATR
    private Double target;          // Measured move

    /**
     * Check if this is a high-quality breakout
     */
    public boolean isHighQuality() {
        return volumeZScore > 2.0           // Very abnormal volume
                && kyleLambda > 0.5         // Institutional activity
                && ofi > 0                  // Buying pressure
                && compressionRatio < 2.0;  // Tight consolidation
    }

    /**
     * Calculate measured move target
     */
    public double calculateMeasuredMove() {
        double range = breakoutHigh - pivotLevel;
        return breakoutHigh + range;
    }
}
