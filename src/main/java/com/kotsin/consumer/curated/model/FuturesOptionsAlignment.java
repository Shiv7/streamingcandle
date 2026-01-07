package com.kotsin.consumer.curated.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * FuturesOptionsAlignment - Combined F&O analysis result
 * Determines if futures and options data align with the trading signal
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FuturesOptionsAlignment {

    private String scripCode;
    private long timestamp;

    // Component data
    private FuturesData futuresData;
    private OptionsData optionsData;

    // Alignment scores (0 to 1)
    private double futuresScore;        // Score from futures analysis
    private double optionsScore;        // Score from options analysis
    private double alignmentScore;      // Combined score

    // Alignment flags
    private boolean isAligned;          // true if score >= 0.6
    private boolean dataAvailable;      // true if both F&O data available
    private boolean dataFresh;          // true if data is < 5 min old

    // Reasons (for transparency)
    @Builder.Default
    private List<String> reasons = new ArrayList<>();

    // Directional bias from F&O
    private DirectionalBias bias;

    public enum DirectionalBias {
        STRONG_BULLISH,     // Both futures and options strongly bullish
        BULLISH,            // Moderately bullish
        NEUTRAL,            // No clear direction
        BEARISH,            // Moderately bearish
        STRONG_BEARISH      // Both futures and options strongly bearish
    }

    /**
     * Check if F&O data is usable
     */
    public boolean isUsable() {
        return dataAvailable && dataFresh && alignmentScore > 0;
    }

    /**
     * Check if F&O strongly supports a LONG trade
     */
    public boolean supportsLong() {
        return isAligned && (bias == DirectionalBias.BULLISH || bias == DirectionalBias.STRONG_BULLISH);
    }

    /**
     * Check if F&O strongly supports a SHORT trade
     */
    public boolean supportsShort() {
        return isAligned && (bias == DirectionalBias.BEARISH || bias == DirectionalBias.STRONG_BEARISH);
    }

    /**
     * Get position size multiplier based on F&O alignment strength
     */
    public double getPositionSizeMultiplier() {
        if (!isAligned) return 0.7;  // Reduce size if not aligned

        if (bias == DirectionalBias.STRONG_BULLISH || bias == DirectionalBias.STRONG_BEARISH) {
            return 1.3;  // Increase size for strong alignment
        }

        return 1.0;  // Normal size
    }

    /**
     * Add a reason for alignment or non-alignment
     */
    public void addReason(String reason) {
        this.reasons.add(reason);
    }
}
