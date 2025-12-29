package com.kotsin.consumer.masterarch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * NormalizedScore - Base model for MASTER ARCHITECTURE scores
 * 
 * GLOBAL CONVENTIONS:
 * - Score ∈ [-1.0, +1.0] (directional)
 * - Strength ∈ [0.0, 1.0] (magnitude)
 * - Certainty ∈ [0.0, 1.0]
 * 
 * All modules compute:
 * - Current_Score
 * - Previous_Score
 * - Delta_Score = Current - Previous
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class NormalizedScore {
    
    /**
     * Current score value
     * Range: [-1.0, +1.0] for directional scores
     *        [0.0, 1.0] for strength/certainty scores
     */
    private double current;
    
    /**
     * Previous bar's score value
     */
    private double previous;
    
    /**
     * Delta = Current - Previous
     * Positive delta = score improving
     * Negative delta = score deteriorating
     */
    private double delta;
    
    /**
     * Timestamp when this score was calculated
     */
    private long timestamp;
    
    /**
     * Optional: confidence in this score [0.0, 1.0]
     */
    private double confidence;
    
    // ======================== FACTORY METHODS ========================
    
    /**
     * Create a new directional score [-1, +1]
     */
    public static NormalizedScore directional(double current, double previous, long timestamp) {
        return NormalizedScore.builder()
                .current(clamp(current, -1.0, 1.0))
                .previous(clamp(previous, -1.0, 1.0))
                .delta(current - previous)
                .timestamp(timestamp)
                .confidence(1.0)
                .build();
    }
    
    /**
     * Create a new strength/magnitude score [0, 1]
     */
    public static NormalizedScore strength(double current, double previous, long timestamp) {
        return NormalizedScore.builder()
                .current(clamp(current, 0.0, 1.0))
                .previous(clamp(previous, 0.0, 1.0))
                .delta(current - previous)
                .timestamp(timestamp)
                .confidence(1.0)
                .build();
    }
    
    /**
     * Create an initial score (no previous data)
     */
    public static NormalizedScore initial(double current, long timestamp, boolean isDirectional) {
        double clampedCurrent = isDirectional 
                ? clamp(current, -1.0, 1.0) 
                : clamp(current, 0.0, 1.0);
        return NormalizedScore.builder()
                .current(clampedCurrent)
                .previous(0.0)
                .delta(clampedCurrent)
                .timestamp(timestamp)
                .confidence(0.5) // Lower confidence for initial scores
                .build();
    }
    
    /**
     * Create empty/neutral score
     */
    public static NormalizedScore neutral(long timestamp) {
        return NormalizedScore.builder()
                .current(0.0)
                .previous(0.0)
                .delta(0.0)
                .timestamp(timestamp)
                .confidence(0.0)
                .build();
    }
    
    // ======================== HELPER METHODS ========================
    
    /**
     * Check if score is positive (bullish direction)
     */
    public boolean isPositive() {
        return current > 0.0;
    }
    
    /**
     * Check if score is negative (bearish direction)
     */
    public boolean isNegative() {
        return current < 0.0;
    }
    
    /**
     * Check if score is improving (delta > 0)
     */
    public boolean isImproving() {
        return delta > 0.0;
    }
    
    /**
     * Check if score is deteriorating (delta < 0)
     */
    public boolean isDeteriorating() {
        return delta < 0.0;
    }
    
    /**
     * Get absolute magnitude of score
     */
    public double magnitude() {
        return Math.abs(current);
    }
    
    /**
     * Get sign of score: +1, -1, or 0
     */
    public int sign() {
        if (current > 0.001) return 1;
        if (current < -0.001) return -1;
        return 0;
    }
    
    /**
     * Apply decay factor
     * Per spec: Score_t = Score_t−1 × 0.90
     */
    public NormalizedScore decay(double factor, long newTimestamp) {
        double decayedCurrent = current * factor;
        return NormalizedScore.builder()
                .current(decayedCurrent)
                .previous(current)
                .delta(decayedCurrent - current)
                .timestamp(newTimestamp)
                .confidence(confidence * factor)
                .build();
    }
    
    /**
     * Clamp value to range
     */
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
