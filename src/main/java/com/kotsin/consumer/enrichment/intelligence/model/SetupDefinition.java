package com.kotsin.consumer.enrichment.intelligence.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * SetupDefinition - Defines a trading setup with required and optional conditions
 *
 * A setup is considered "ready" when all required conditions are met.
 * Booster conditions add confidence but are not required.
 * Invalidation conditions kill the setup if any is met.
 *
 * Examples:
 * - SCALP_REVERSAL_LONG: Price at support + OFI positive + Volume positive
 * - SWING_LONG: SuperTrend bullish + OFI buy regime + OI buildup + HTF alignment
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SetupDefinition {

    /**
     * Unique setup ID (e.g., "SCALP_REVERSAL_LONG")
     */
    private String setupId;

    /**
     * Human-readable description
     */
    private String description;

    /**
     * Trading horizon
     */
    private SetupHorizon horizon;

    /**
     * Trade direction
     */
    private SetupDirection direction;

    /**
     * Category of setup
     */
    private SetupCategory category;

    // ======================== CONDITIONS ========================

    /**
     * Required conditions - ALL must be true for setup to be valid
     */
    private List<SetupCondition> requiredConditions;

    /**
     * Booster conditions - Add confidence when met
     */
    private List<SetupCondition> boosterConditions;

    /**
     * Invalidation conditions - ANY being true kills the setup
     */
    private List<SetupCondition> invalidationConditions;

    // ======================== CONFIDENCE ========================

    /**
     * Base confidence when all required conditions met (0-1)
     */
    @Builder.Default
    private double baseConfidence = 0.60;

    /**
     * Maximum confidence with all boosters (0-1)
     */
    @Builder.Default
    private double maxConfidence = 0.90;

    /**
     * Minimum confidence to consider setup actionable
     */
    @Builder.Default
    private double minActionableConfidence = 0.55;

    // ======================== TRADE PARAMETERS ========================

    /**
     * Entry logic description
     */
    private String entryLogic;

    /**
     * Stop loss logic description
     */
    private String stopLogic;

    /**
     * Target logic description
     */
    private String targetLogic;

    /**
     * Default risk/reward ratio
     */
    @Builder.Default
    private double defaultRiskReward = 2.0;

    /**
     * Default stop loss percentage from entry
     */
    @Builder.Default
    private double defaultStopPct = 0.5;

    /**
     * Default target percentage from entry
     */
    @Builder.Default
    private double defaultTargetPct = 1.0;

    // ======================== HISTORICAL STATS ========================

    /**
     * Historical success rate
     */
    @Builder.Default
    private double historicalSuccessRate = 0.0;

    /**
     * Historical occurrences
     */
    @Builder.Default
    private int historicalOccurrences = 0;

    /**
     * Historical expected value
     */
    @Builder.Default
    private double historicalExpectedValue = 0.0;

    // ======================== ENUMS ========================

    public enum SetupHorizon {
        SCALP,      // 5-30 minutes
        SWING,      // 1-4 hours
        POSITIONAL  // 1-5 days
    }

    public enum SetupDirection {
        LONG,
        SHORT,
        EITHER
    }

    public enum SetupCategory {
        REVERSAL,       // Price bounce from S/R
        BREAKOUT,       // Price breaking S/R
        CONTINUATION,   // Trend continuation
        MOMENTUM,       // Strong directional move
        MEAN_REVERSION  // Return to mean
    }

    // ======================== HELPER METHODS ========================

    /**
     * Get total number of required conditions
     */
    public int getRequiredCount() {
        return requiredConditions != null ? requiredConditions.size() : 0;
    }

    /**
     * Get total number of booster conditions
     */
    public int getBoosterCount() {
        return boosterConditions != null ? boosterConditions.size() : 0;
    }

    /**
     * Calculate confidence boost per booster condition
     */
    public double getBoosterConfidenceEach() {
        int boosterCount = getBoosterCount();
        if (boosterCount == 0) return 0;
        return (maxConfidence - baseConfidence) / boosterCount;
    }

    /**
     * Check if setup has sufficient historical data
     */
    public boolean hasSufficientHistory() {
        return historicalOccurrences >= 30;
    }

    /**
     * Check if setup is valid
     */
    public boolean isValid() {
        return setupId != null && !setupId.isEmpty()
                && requiredConditions != null && !requiredConditions.isEmpty()
                && horizon != null
                && direction != null;
    }

    @Override
    public String toString() {
        return String.format("Setup[%s] %s %s: %d required, %d boosters (base=%.0f%%, max=%.0f%%)",
                setupId, horizon, direction,
                getRequiredCount(), getBoosterCount(),
                baseConfidence * 100, maxConfidence * 100);
    }
}
