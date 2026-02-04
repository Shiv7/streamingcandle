package com.kotsin.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CprAnalysis - Result of CPR (Central Pivot Range) analysis.
 *
 * Thin CPR indicates high probability of breakout.
 * Wide CPR indicates range-bound/mean-reversion market.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CprAnalysis {

    private double cprWidth;
    private double cprWidthPercent;
    private CprType type;
    private PricePosition pricePosition;
    private double breakoutProbability;

    // CPR levels for reference
    private double tc;  // Top Central
    private double bc;  // Bottom Central
    private double pivot;

    /**
     * CPR type based on width.
     */
    public enum CprType {
        ULTRA_THIN,  // < 0.3% - Very high breakout probability
        THIN,        // 0.3% - 0.5% - High breakout probability
        NORMAL,      // 0.5% - 1.0% - Normal market
        WIDE         // > 1.0% - Range-bound, mean reversion expected
    }

    /**
     * Price position relative to CPR.
     */
    public enum PricePosition {
        ABOVE_CPR,   // Bullish bias - price above top central
        INSIDE_CPR,  // Consolidating - price within CPR
        BELOW_CPR    // Bearish bias - price below bottom central
    }

    /**
     * Get trading bias based on price position.
     */
    public String getTradingBias() {
        if (pricePosition == null) return "NEUTRAL";

        return switch (pricePosition) {
            case ABOVE_CPR -> "BULLISH";
            case BELOW_CPR -> "BEARISH";
            case INSIDE_CPR -> "NEUTRAL";
        };
    }

    /**
     * Get trading recommendation based on CPR analysis.
     */
    public String getRecommendation() {
        if (type == null || pricePosition == null) return "NO_DATA";

        if (type == CprType.ULTRA_THIN || type == CprType.THIN) {
            // High breakout probability
            if (pricePosition == PricePosition.ABOVE_CPR) {
                return "BREAKOUT_LONG";
            } else if (pricePosition == PricePosition.BELOW_CPR) {
                return "BREAKOUT_SHORT";
            } else {
                return "WAIT_FOR_BREAKOUT";
            }
        } else if (type == CprType.WIDE) {
            // Mean reversion
            if (pricePosition == PricePosition.ABOVE_CPR) {
                return "FADE_TO_PIVOT";  // Expect pullback
            } else if (pricePosition == PricePosition.BELOW_CPR) {
                return "BOUNCE_TO_PIVOT"; // Expect bounce
            } else {
                return "RANGE_TRADE";
            }
        } else {
            return "NORMAL_TRADE";
        }
    }

    /**
     * Check if this is a high-probability setup.
     */
    public boolean isHighProbability() {
        return breakoutProbability >= 0.6;
    }
}
