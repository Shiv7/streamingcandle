package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MTFDistribution - Multi-Timeframe Distribution Metrics
 * 
 * PHASE 2 ENHANCEMENT: Tracks intra-window characteristics
 * 
 * Answers questions like:
 * - Were all 5 sub-candles bullish (strong trend) or mixed (weak/reversal)?
 * - Did volume spike early (exhaustion) or late (continuation)?
 * - Is momentum accelerating or decelerating?
 * 
 * Use Cases:
 * - "5 bullish 1m candles" in 5m aggregate = strong continuation likely
 * - "3 bullish, 2 bearish" = indecision, wait for clarity
 * - "Volume spike in 1st candle then dry" = weak follow-through
 * - "Momentum accelerating" = trend strengthening
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MTFDistribution {
    
    // ========== Directional Distribution ==========
    
    /**
     * Number of bullish sub-candles (close > open)
     * Range: 0 to totalSubCandles
     */
    private int bullishSubCandles;
    
    /**
     * Number of bearish sub-candles (close < open)
     * Range: 0 to totalSubCandles
     */
    private int bearishSubCandles;
    
    /**
     * Total number of sub-candles in aggregation window
     */
    private int totalSubCandles;
    
    /**
     * Directional consistency (0 to 1)
     * 1.0 = All candles same direction (strong trend)
     * 0.0 = Equal bullish/bearish (indecision)
     * 
     * Formula: |bullish - bearish| / total
     */
    private double directionalConsistency;
    
    /**
     * Dominant direction: BULLISH, BEARISH, or NEUTRAL
     */
    private Direction dominantDirection;
    
    public enum Direction {
        BULLISH,    // More bullish candles
        BEARISH,    // More bearish candles
        NEUTRAL     // Equal or nearly equal
    }
    
    // ========== Volume Distribution ==========
    
    /**
     * Index of candle with highest volume (0-based)
     * -1 if not available
     * 
     * Interpretation:
     * 0-1 = Early spike (potential exhaustion)
     * Middle = Normal distribution
     * Last = Late spike (continuation power)
     */
    private int volumeSpikeCandleIndex;
    
    /**
     * Ratio of volume in first 40% of candles
     * Range: 0 to 1
     * 
     * > 0.6 = Front-loaded (early exhaustion risk)
     * < 0.25 = Back-loaded (building momentum)
     */
    private double earlyVolumeRatio;
    
    /**
     * Ratio of volume in last 40% of candles
     * Range: 0 to 1
     */
    private double lateVolumeRatio;
    
    /**
     * True if volume declining through window
     * Indicates potential exhaustion
     */
    private boolean volumeDrying;
    
    // ========== Momentum Evolution ==========
    
    /**
     * Average momentum of early candles (first 40%)
     * Momentum = (close - open) / (high - low)
     */
    private double earlyMomentum;
    
    /**
     * Average momentum of late candles (last 40%)
     */
    private double lateMomentum;
    
    /**
     * Momentum shift (late - early)
     * 
     * > 0.2 = Accelerating (strengthening trend)
     * < -0.2 = Decelerating (weakening, reversal risk)
     * -0.2 to 0.2 = Steady
     */
    private double momentumShift;
    
    /**
     * True if momentum accelerating (shift > 0.1)
     */
    private boolean momentumAccelerating;
    
    /**
     * True if momentum decelerating (shift < -0.1)
     */
    private boolean momentumDecelerating;
    
    // ========== Confidence Metrics ==========
    
    /**
     * Confidence in distribution analysis (0 to 1)
     * Based on number of sub-candles (more = better)
     * 
     * < 3 candles = low confidence (0.3)
     * 3-4 candles = medium (0.6)
     * 5+ candles = high (1.0)
     */
    private double confidence;
    
    // ========== Convenience Methods ==========
    
    /**
     * Is this a strong trend setup?
     * - High directional consistency (> 0.7)
     * - Momentum not decelerating
     */
    public boolean isStrongTrend() {
        return directionalConsistency > 0.7 && !momentumDecelerating;
    }
    
    /**
     * Is this showing exhaustion signs?
     * - Early volume spike
     * - Volume drying
     * - Momentum decelerating
     */
    public boolean showsExhaustion() {
        return (volumeSpikeCandleIndex <= 1 || volumeDrying) && momentumDecelerating;
    }
    
    /**
     * Is this building strength?
     * - Late volume
     * - Momentum accelerating
     */
    public boolean buildingStrength() {
        return lateVolumeRatio > 0.4 && momentumAccelerating;
    }
    
    /**
     * Get human-readable interpretation
     */
    public String getInterpretation() {
        if (isStrongTrend()) {
            return "STRONG_TREND";
        } else if (showsExhaustion()) {
            return "EXHAUSTION";
        } else if (buildingStrength()) {
            return "BUILDING_STRENGTH";
        } else if (directionalConsistency < 0.3) {
            return "INDECISION";
        } else {
            return "NORMAL";
        }
    }
}
