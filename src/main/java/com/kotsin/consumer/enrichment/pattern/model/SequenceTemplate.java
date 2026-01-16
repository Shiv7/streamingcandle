package com.kotsin.consumer.enrichment.pattern.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * SequenceTemplate - Defines a predictive pattern (sequence of events)
 *
 * A pattern is a sequence of market events that historically predicts
 * a specific outcome with measurable probability.
 *
 * Example: REVERSAL_FROM_SUPPORT
 * - Required: SELLING_EXHAUSTION → OFI_FLIP → ABSORPTION
 * - Boosters: PUT_OI_UNWINDING, CALL_OI_SURGE
 * - Outcome: Price +0.5-1.5% in 30 minutes
 * - Historical: 847 occurrences, 73% success rate
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SequenceTemplate {

    /**
     * Unique pattern identifier (e.g., "REVERSAL_FROM_SUPPORT")
     */
    private String templateId;

    /**
     * Human-readable description
     */
    private String description;

    /**
     * Pattern category for grouping
     */
    private PatternCategory category;

    /**
     * Trading direction when pattern completes
     */
    private PatternDirection direction;

    /**
     * Trading horizon (SCALP, SWING, POSITIONAL)
     */
    private TradingHorizon horizon;

    // ======================== EVENT CONDITIONS ========================

    /**
     * Required events that MUST occur in order for pattern to complete.
     * Events must occur in sequence within maxDuration.
     */
    @Builder.Default
    private List<EventCondition> requiredEvents = new ArrayList<>();

    /**
     * Booster events that add confidence if they occur.
     * These are optional but increase probability.
     */
    @Builder.Default
    private List<EventCondition> boosterEvents = new ArrayList<>();

    /**
     * Invalidation events that kill the pattern if any occur.
     * Pattern is immediately terminated if any of these fire.
     */
    @Builder.Default
    private List<EventCondition> invalidationEvents = new ArrayList<>();

    // ======================== TIMING ========================

    /**
     * Maximum duration for pattern to complete from first event.
     * Pattern expires if not completed within this window.
     */
    @Builder.Default
    private Duration maxDuration = Duration.ofMinutes(30);

    /**
     * Minimum duration between events (to avoid noise)
     */
    @Builder.Default
    private Duration minEventSpacing = Duration.ofSeconds(30);

    // ======================== EXPECTED OUTCOME ========================

    /**
     * What should happen when pattern completes
     */
    private ExpectedOutcome expectedOutcome;

    // ======================== HISTORICAL STATS ========================

    /**
     * Statistics from backtesting (loaded from MongoDB)
     */
    private HistoricalStats historicalStats;

    // ======================== CONFIDENCE ========================

    /**
     * Base confidence when all required events match (0-1)
     */
    @Builder.Default
    private double baseConfidence = 0.60;

    /**
     * Maximum confidence with all boosters (0-1)
     */
    @Builder.Default
    private double maxConfidence = 0.90;

    /**
     * Minimum confidence to emit signal (0-1)
     */
    @Builder.Default
    private double minConfidenceToSignal = 0.55;

    // ======================== ENUMS ========================

    public enum PatternCategory {
        REVERSAL,           // Price reversal patterns
        CONTINUATION,       // Trend continuation
        BREAKOUT,           // Breakout patterns
        SQUEEZE,            // Gamma/volatility squeeze
        EXHAUSTION          // Buying/selling exhaustion
    }

    public enum PatternDirection {
        BULLISH,
        BEARISH,
        NEUTRAL             // Could go either way
    }

    public enum TradingHorizon {
        SCALP,              // 5-30 minute holding
        SWING,              // 1-5 day holding
        POSITIONAL          // 5+ day holding
    }

    // ======================== HELPER METHODS ========================

    /**
     * Get total required event count
     */
    public int getRequiredEventCount() {
        return requiredEvents != null ? requiredEvents.size() : 0;
    }

    /**
     * Get total booster event count
     */
    public int getBoosterEventCount() {
        return boosterEvents != null ? boosterEvents.size() : 0;
    }

    /**
     * Calculate confidence boost per booster event
     */
    public double getBoosterConfidenceIncrement() {
        int boosterCount = getBoosterEventCount();
        if (boosterCount == 0) return 0;
        return (maxConfidence - baseConfidence) / boosterCount;
    }

    /**
     * Check if pattern is valid (has required events and outcome)
     */
    public boolean isValid() {
        return templateId != null && !templateId.isEmpty()
                && requiredEvents != null && !requiredEvents.isEmpty()
                && expectedOutcome != null;
    }

    /**
     * Get historical success rate or default
     */
    public double getSuccessRate() {
        if (historicalStats != null && historicalStats.getOccurrences() >= 10) {
            return historicalStats.getSuccessRate();
        }
        return baseConfidence; // Use base confidence as default
    }

    /**
     * Get expected value per trade
     */
    public double getExpectedValue() {
        if (historicalStats != null && historicalStats.getOccurrences() >= 10) {
            return historicalStats.getExpectedValue();
        }
        return 0; // Unknown
    }

    /**
     * Check if we have sufficient historical data
     */
    public boolean hasSufficientHistory() {
        return historicalStats != null && historicalStats.getOccurrences() >= 50;
    }

    @Override
    public String toString() {
        return String.format("%s [%s %s] - %d required events, %.0f%% base confidence",
                templateId, direction, horizon,
                getRequiredEventCount(), baseConfidence * 100);
    }
}
