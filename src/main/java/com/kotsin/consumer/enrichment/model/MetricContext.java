package com.kotsin.consumer.enrichment.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * MetricContext - Contains historical context for a single metric
 *
 * Used by HistoricalContextEnricher to add memory to each FamilyCandle.
 * Replaces stateless processing with stateful context-aware processing.
 *
 * Key Statistics:
 * - mean: Average value over the rolling window
 * - stddev: Standard deviation for z-score calculation
 * - zscore: How many standard deviations from mean (current - mean) / stddev
 * - percentile: Where current value ranks [0, 100]
 *
 * Regime Detection:
 * - regime: STRONG_POSITIVE, POSITIVE, NEUTRAL, NEGATIVE, STRONG_NEGATIVE
 * - consecutiveCount: How many candles in the same direction
 * - flipDetected: Did the regime just change?
 * - flipMagnitude: How big was the flip?
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricContext {

    // Metric identification
    private String metricName;
    private String familyId;
    private String timeframe;

    // Current value
    private double currentValue;
    private double previousValue;

    // Rolling history (last N values)
    private List<Double> history;
    private int historySize;

    // Statistics
    private double mean;
    private double stddev;
    private double zscore;
    private double percentile;

    // Regime detection
    private MetricRegime regime;
    private MetricRegime previousRegime;
    private int consecutiveCount;

    // Flip detection
    private boolean flipDetected;
    private FlipType flipType;
    private double flipMagnitude;
    private double flipZscore;

    // Learning mode
    private boolean inLearningMode;
    private int samplesCollected;
    private static final int MIN_SAMPLES_FOR_STATS = 20;

    /**
     * Check if we have enough samples for meaningful statistics
     */
    public boolean hasEnoughSamples() {
        return samplesCollected >= MIN_SAMPLES_FOR_STATS;
    }

    /**
     * Check if current value is extreme (beyond 2 standard deviations)
     */
    public boolean isExtreme() {
        return Math.abs(zscore) > 2.0;
    }

    /**
     * Check if this is a bullish regime
     */
    public boolean isBullish() {
        return regime == MetricRegime.STRONG_POSITIVE || regime == MetricRegime.POSITIVE;
    }

    /**
     * Check if this is a bearish regime
     */
    public boolean isBearish() {
        return regime == MetricRegime.STRONG_NEGATIVE || regime == MetricRegime.NEGATIVE;
    }

    /**
     * Get confidence modifier based on regime strength
     */
    public double getConfidenceModifier() {
        if (!hasEnoughSamples()) {
            return 0.5; // Reduced confidence in learning mode
        }

        return switch (regime) {
            case STRONG_POSITIVE, STRONG_NEGATIVE -> 1.2;
            case POSITIVE, NEGATIVE -> 1.0;
            case NEUTRAL -> 0.8;
        };
    }

    /**
     * Metric regime classification
     */
    public enum MetricRegime {
        STRONG_POSITIVE,  // percentile > 80 AND consecutive >= 3
        POSITIVE,         // percentile > 60 OR consecutive >= 2
        NEUTRAL,          // between 40-60 percentile
        NEGATIVE,         // percentile < 40 OR consecutive >= 2 (negative)
        STRONG_NEGATIVE   // percentile < 20 AND consecutive >= 3 (negative)
    }

    /**
     * Flip type for regime transitions
     */
    public enum FlipType {
        BEARISH_TO_BULLISH,
        BULLISH_TO_BEARISH,
        NEUTRAL_TO_BULLISH,
        NEUTRAL_TO_BEARISH,
        BULLISH_TO_NEUTRAL,
        BEARISH_TO_NEUTRAL,
        NONE
    }

    /**
     * Create a learning mode context (insufficient data)
     */
    public static MetricContext learningMode(String metricName, String familyId, String timeframe,
                                              double currentValue, int samplesCollected) {
        return MetricContext.builder()
                .metricName(metricName)
                .familyId(familyId)
                .timeframe(timeframe)
                .currentValue(currentValue)
                .inLearningMode(true)
                .samplesCollected(samplesCollected)
                .regime(MetricRegime.NEUTRAL)
                .flipDetected(false)
                .flipType(FlipType.NONE)
                .build();
    }
}
