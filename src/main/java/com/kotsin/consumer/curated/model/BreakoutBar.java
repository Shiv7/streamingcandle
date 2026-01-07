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
    private double pivotLevel;      // Old resistance becomes new support (or old support for breakdown)
    private double compressionRatio; // How tight was the consolidation
    
    // Direction: BULLISH for upside breakout, BEARISH for downside breakdown
    @Builder.Default
    private String direction = "BULLISH";

    // Entry/Stop levels (calculated later during retest)
    private Double retestEntry;     // Entry on retest at pivot
    private Double stopLoss;        // Below pivot - 1 ATR (or above for shorts)
    private Double target;          // Measured move

    /**
     * Check if this is a high-quality breakout
     */
    public boolean isHighQuality() {
        boolean volumeOk = volumeZScore > 2.0;           // Very abnormal volume
        boolean institutionalOk = kyleLambda > 0.5;      // Institutional activity
        boolean compressionOk = compressionRatio < 2.0;  // Tight consolidation
        
        // Flow direction must match breakout direction
        boolean flowOk = "BULLISH".equals(direction) ? ofi > 0 : ofi < 0;
        
        return volumeOk && institutionalOk && flowOk && compressionOk;
    }
    
    /**
     * Check if this is a bullish breakout
     */
    public boolean isBullish() {
        return "BULLISH".equals(direction);
    }
    
    /**
     * Check if this is a bearish breakdown
     */
    public boolean isBearish() {
        return "BEARISH".equals(direction);
    }

    /**
     * Calculate measured move target
     */
    public double calculateMeasuredMove() {
        if (isBullish()) {
            double range = breakoutHigh - pivotLevel;
            return breakoutHigh + range;
        } else {
            double range = pivotLevel - breakoutLow;
            return breakoutLow - range;
        }
    }
}
