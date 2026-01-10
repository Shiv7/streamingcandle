package com.kotsin.consumer.enrichment.pattern.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;

/**
 * ExpectedOutcome - Defines what should happen when a pattern completes
 *
 * This is used for:
 * 1. Setting trade targets and stops
 * 2. Measuring pattern success/failure
 * 3. Calculating expected value
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpectedOutcome {

    /**
     * Description of expected price action
     */
    private String description;

    // ======================== PRICE TARGETS ========================

    /**
     * Minimum expected price move (percentage)
     * E.g., 0.5 = expect at least 0.5% move
     */
    @Builder.Default
    private double minPriceMovePct = 0.3;

    /**
     * Maximum expected price move (percentage)
     * E.g., 1.5 = expect up to 1.5% move
     */
    @Builder.Default
    private double maxPriceMovePct = 1.5;

    /**
     * Target 1 price move (percentage) - partial exit
     */
    @Builder.Default
    private double target1Pct = 0.5;

    /**
     * Target 2 price move (percentage) - full exit
     */
    @Builder.Default
    private double target2Pct = 1.0;

    /**
     * Target 3 price move (percentage) - extended target
     */
    @Builder.Default
    private double target3Pct = 1.5;

    // ======================== STOP LOSS ========================

    /**
     * Recommended stop loss (percentage from entry)
     */
    @Builder.Default
    private double stopLossPct = 0.5;

    /**
     * Use ATR-based stop loss instead of fixed percentage
     */
    @Builder.Default
    private boolean useAtrStop = false;

    /**
     * ATR multiplier for stop loss (if useAtrStop is true)
     */
    @Builder.Default
    private double atrMultiplier = 1.5;

    // ======================== TIMING ========================

    /**
     * Expected timeframe for outcome
     */
    @Builder.Default
    private Duration expectedTimeframe = Duration.ofMinutes(30);

    /**
     * Minimum hold time before exit
     */
    @Builder.Default
    private Duration minHoldTime = Duration.ofMinutes(5);

    /**
     * Maximum hold time (exit if not hit targets)
     */
    @Builder.Default
    private Duration maxHoldTime = Duration.ofHours(4);

    // ======================== PREDICTED EVENTS ========================

    /**
     * Predicted follow-on events (for forecasting)
     * E.g., "SUPERTREND_FLIP" expected within 15-25 min
     */
    private String[] predictedEvents;

    /**
     * Predicted event timeframe description
     */
    private String predictedEventTimeframe;

    // ======================== RISK/REWARD ========================

    /**
     * Minimum acceptable risk/reward ratio
     */
    @Builder.Default
    private double minRiskReward = 1.5;

    /**
     * Target risk/reward ratio
     */
    @Builder.Default
    private double targetRiskReward = 2.0;

    // ======================== CONFIRMATION ========================

    /**
     * How to confirm pattern success
     */
    private ConfirmationType confirmationType;

    /**
     * Threshold for confirmation (percentage or absolute based on type)
     */
    @Builder.Default
    private double confirmationThreshold = 0.5;

    public enum ConfirmationType {
        PRICE_MOVE_PCT,         // Price moves X% in expected direction
        PRICE_MOVE_POINTS,      // Price moves X points
        TARGET_HIT,             // Price hits target level
        PREDICTED_EVENT,        // Predicted event occurs
        SUPERTREND_FLIP,        // SuperTrend flips in expected direction
        TIME_BASED              // Measure at specific time
    }

    // ======================== FACTORY METHODS ========================

    /**
     * Create a scalp outcome (small moves, quick exits)
     */
    public static ExpectedOutcome scalp(double targetPct, double stopPct) {
        return ExpectedOutcome.builder()
                .description("Scalp trade: quick in/out")
                .minPriceMovePct(targetPct * 0.5)
                .maxPriceMovePct(targetPct * 2)
                .target1Pct(targetPct * 0.6)
                .target2Pct(targetPct)
                .target3Pct(targetPct * 1.5)
                .stopLossPct(stopPct)
                .expectedTimeframe(Duration.ofMinutes(15))
                .minHoldTime(Duration.ofMinutes(2))
                .maxHoldTime(Duration.ofMinutes(60))
                .minRiskReward(1.5)
                .confirmationType(ConfirmationType.PRICE_MOVE_PCT)
                .confirmationThreshold(targetPct * 0.5)
                .build();
    }

    /**
     * Create a swing outcome (larger moves, longer holds)
     */
    public static ExpectedOutcome swing(double targetPct, double stopPct) {
        return ExpectedOutcome.builder()
                .description("Swing trade: trend following")
                .minPriceMovePct(targetPct * 0.3)
                .maxPriceMovePct(targetPct * 3)
                .target1Pct(targetPct * 0.5)
                .target2Pct(targetPct)
                .target3Pct(targetPct * 2)
                .stopLossPct(stopPct)
                .useAtrStop(true)
                .atrMultiplier(2.0)
                .expectedTimeframe(Duration.ofHours(4))
                .minHoldTime(Duration.ofMinutes(30))
                .maxHoldTime(Duration.ofDays(3))
                .minRiskReward(2.0)
                .confirmationType(ConfirmationType.SUPERTREND_FLIP)
                .build();
    }

    /**
     * Create a reversal outcome
     */
    public static ExpectedOutcome reversal(double targetPct) {
        return ExpectedOutcome.builder()
                .description("Reversal: bounce from support/resistance")
                .minPriceMovePct(0.3)
                .maxPriceMovePct(1.5)
                .target1Pct(targetPct * 0.5)
                .target2Pct(targetPct)
                .target3Pct(targetPct * 1.5)
                .stopLossPct(targetPct * 0.5)
                .expectedTimeframe(Duration.ofMinutes(30))
                .minHoldTime(Duration.ofMinutes(5))
                .maxHoldTime(Duration.ofHours(2))
                .minRiskReward(1.5)
                .predictedEvents(new String[]{"SUPERTREND_FLIP"})
                .predictedEventTimeframe("15-30 minutes")
                .confirmationType(ConfirmationType.PRICE_MOVE_PCT)
                .confirmationThreshold(targetPct * 0.5)
                .build();
    }

    /**
     * Create a breakout outcome
     */
    public static ExpectedOutcome breakout(double targetPct) {
        return ExpectedOutcome.builder()
                .description("Breakout: momentum continuation")
                .minPriceMovePct(0.5)
                .maxPriceMovePct(3.0)
                .target1Pct(targetPct * 0.5)
                .target2Pct(targetPct)
                .target3Pct(targetPct * 2)
                .stopLossPct(targetPct * 0.3)
                .expectedTimeframe(Duration.ofMinutes(45))
                .minHoldTime(Duration.ofMinutes(5))
                .maxHoldTime(Duration.ofHours(4))
                .minRiskReward(2.5)
                .confirmationType(ConfirmationType.PRICE_MOVE_PCT)
                .confirmationThreshold(targetPct * 0.3)
                .build();
    }

    /**
     * Create a gamma squeeze outcome
     */
    public static ExpectedOutcome gammaSqueeze(double strikeDistance) {
        return ExpectedOutcome.builder()
                .description("Gamma squeeze: dealer hedging flow")
                .minPriceMovePct(strikeDistance)
                .maxPriceMovePct(strikeDistance * 3)
                .target1Pct(strikeDistance)
                .target2Pct(strikeDistance * 2)
                .target3Pct(strikeDistance * 3)
                .stopLossPct(strikeDistance * 0.5)
                .expectedTimeframe(Duration.ofMinutes(30))
                .minHoldTime(Duration.ofMinutes(5))
                .maxHoldTime(Duration.ofHours(2))
                .minRiskReward(2.0)
                .confirmationType(ConfirmationType.TARGET_HIT)
                .confirmationThreshold(strikeDistance)
                .build();
    }

    // ======================== HELPER METHODS ========================

    /**
     * Calculate risk/reward ratio based on targets and stop
     */
    public double calculateRiskReward() {
        if (stopLossPct <= 0) return 0;
        return target2Pct / stopLossPct;
    }

    /**
     * Check if outcome is valid
     */
    public boolean isValid() {
        return target1Pct > 0 && stopLossPct > 0 && calculateRiskReward() >= minRiskReward;
    }

    @Override
    public String toString() {
        return String.format("Target: %.1f%% / Stop: %.1f%% (R:R=%.1f) within %s",
                target2Pct, stopLossPct, calculateRiskReward(),
                formatDuration(expectedTimeframe));
    }

    private String formatDuration(Duration d) {
        if (d == null) return "?";
        long mins = d.toMinutes();
        if (mins < 60) return mins + "m";
        if (mins < 1440) return (mins / 60) + "h";
        return (mins / 1440) + "d";
    }
}
