package com.kotsin.consumer.enrichment.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * ExpiryContext - Days-to-Expiry awareness for options trading
 *
 * Options behave differently based on time to expiry:
 * - Far DTE (>7): Theta decay slow, gamma low, trends matter more
 * - Near DTE (3-7): Theta accelerating, gamma increasing
 * - Expiry week (<3): Gamma explosion, theta crush, max pain matters
 * - Expiry day: Extreme gamma, pin risk, max pain very relevant
 *
 * This context helps:
 * - Adjust signal confidence based on DTE
 * - Apply max pain signals (strongest on expiry day)
 * - Warn about theta crush for long options
 * - Boost gamma-related signals near expiry
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpiryContext {

    // ======================== IDENTITY ========================

    private String familyId;
    private LocalDate currentDate;
    private LocalTime currentTime;

    // ======================== EXPIRY INFO ========================

    /**
     * Nearest expiry date for this instrument
     */
    private LocalDate nearestExpiry;

    /**
     * Days to nearest expiry
     */
    private int daysToExpiry;

    /**
     * Fractional DTE (includes time of day)
     * E.g., on expiry day at 10am, this might be 0.2
     */
    private double fractionalDTE;

    /**
     * Expiry category for quick classification
     */
    private ExpiryCategory category;

    // ======================== TRADING FLAGS ========================

    /**
     * Is today the expiry day?
     */
    private boolean isExpiryDay;

    /**
     * Is this expiry week (DTE <= 3)?
     */
    private boolean isExpiryWeek;

    /**
     * Is this near expiry (DTE <= 7)?
     */
    private boolean isNearExpiry;

    /**
     * Is it expiry day afternoon (max pain most relevant)?
     * True if expiry day and after 12:00
     */
    private boolean isExpiryDayAfternoon;

    // ======================== SIGNAL MODIFIERS ========================

    /**
     * Max pain relevance (0-1)
     * 0 = far from expiry (ignore max pain)
     * 1 = expiry day afternoon (max pain very relevant)
     */
    private double maxPainRelevance;

    /**
     * Gamma significance (0-1)
     * Higher near expiry = gamma moves become more important
     */
    private double gammaSignificance;

    /**
     * Theta warning level (0-1)
     * Higher near expiry = warn about holding long options
     */
    private double thetaWarningLevel;

    /**
     * Overall confidence modifier for options signals
     */
    private double confidenceModifier;

    // ======================== RECOMMENDATIONS ========================

    /**
     * Should we apply max pain analysis?
     */
    private boolean applyMaxPain;

    /**
     * Recommendation for options trading
     */
    private String tradingRecommendation;

    // ======================== ENUMS ========================

    public enum ExpiryCategory {
        /**
         * More than 14 days to expiry
         * Gamma low, theta slow, position sizing normal
         */
        FAR_EXPIRY,

        /**
         * 8-14 days to expiry
         * Theta accelerating, be aware of time decay
         */
        MEDIUM_EXPIRY,

        /**
         * 4-7 days to expiry
         * Gamma increasing, theta significant
         */
        NEAR_EXPIRY,

        /**
         * 1-3 days to expiry
         * Gamma high, theta crushing, max pain relevant
         */
        EXPIRY_WEEK,

        /**
         * Expiry day (0 DTE)
         * Extreme gamma, pin risk, max pain very important
         */
        EXPIRY_DAY
    }

    // ======================== HELPER METHODS ========================

    /**
     * Check if max pain signals should be applied
     */
    public boolean shouldApplyMaxPain() {
        return applyMaxPain && maxPainRelevance > 0.3;
    }

    /**
     * Check if we should warn about theta decay
     */
    public boolean shouldWarnTheta() {
        return thetaWarningLevel > 0.5;
    }

    /**
     * Get signal modifier for long options (penalize near expiry due to theta)
     */
    public double getLongOptionsModifier() {
        // Penalize long options more as expiry approaches
        return Math.max(0.3, 1.0 - thetaWarningLevel * 0.5);
    }

    /**
     * Get signal modifier for short options (boost near expiry due to theta edge)
     */
    public double getShortOptionsModifier() {
        // Slight boost for short options near expiry (theta in favor)
        // But also more risk, so moderate boost
        return 1.0 + thetaWarningLevel * 0.2;
    }

    /**
     * Get category description
     */
    public String getCategoryDescription() {
        if (category == null) return "Unknown";

        return switch (category) {
            case FAR_EXPIRY -> String.format("Far expiry (%d DTE) - Normal trading, low gamma", daysToExpiry);
            case MEDIUM_EXPIRY -> String.format("Medium expiry (%d DTE) - Theta accelerating", daysToExpiry);
            case NEAR_EXPIRY -> String.format("Near expiry (%d DTE) - Gamma rising, watch theta", daysToExpiry);
            case EXPIRY_WEEK -> String.format("Expiry week (%d DTE) - HIGH gamma, max pain relevant", daysToExpiry);
            case EXPIRY_DAY -> "EXPIRY DAY - Extreme gamma, pin risk, max pain critical";
        };
    }

    /**
     * Get risk level description
     */
    public String getRiskLevel() {
        if (category == null) return "UNKNOWN";
        return switch (category) {
            case FAR_EXPIRY, MEDIUM_EXPIRY -> "NORMAL";
            case NEAR_EXPIRY -> "ELEVATED";
            case EXPIRY_WEEK -> "HIGH";
            case EXPIRY_DAY -> "EXTREME";
        };
    }

    /**
     * Factory method for non-options context
     */
    public static ExpiryContext notApplicable() {
        return ExpiryContext.builder()
                .daysToExpiry(-1)
                .fractionalDTE(-1)
                .category(null)
                .isExpiryDay(false)
                .isExpiryWeek(false)
                .isNearExpiry(false)
                .isExpiryDayAfternoon(false)
                .maxPainRelevance(0)
                .gammaSignificance(0)
                .thetaWarningLevel(0)
                .confidenceModifier(1.0)
                .applyMaxPain(false)
                .tradingRecommendation("N/A - Not an options instrument")
                .build();
    }

    /**
     * Factory method for empty context
     */
    public static ExpiryContext empty() {
        return ExpiryContext.builder()
                .daysToExpiry(0)
                .fractionalDTE(0)
                .category(ExpiryCategory.EXPIRY_DAY)
                .isExpiryDay(true)
                .isExpiryWeek(true)
                .isNearExpiry(true)
                .isExpiryDayAfternoon(false)
                .maxPainRelevance(0.8)
                .gammaSignificance(1.0)
                .thetaWarningLevel(1.0)
                .confidenceModifier(0.7)
                .applyMaxPain(true)
                .tradingRecommendation("Expiry day - use caution")
                .build();
    }
}
