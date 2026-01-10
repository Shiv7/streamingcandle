package com.kotsin.consumer.enrichment.pattern.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * HistoricalStats - Statistics from backtesting/live trading for a pattern
 *
 * These stats are used to:
 * 1. Calculate pattern probability
 * 2. Set position sizing
 * 3. Filter low-quality patterns
 *
 * Stats are stored in MongoDB and updated after each pattern outcome.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoricalStats {

    /**
     * Pattern template ID
     */
    private String templateId;

    /**
     * Family ID (null for global stats)
     */
    private String familyId;

    // ======================== OCCURRENCE COUNTS ========================

    /**
     * Total number of times pattern was detected
     */
    @Builder.Default
    private int occurrences = 0;

    /**
     * Number of successful outcomes (price moved as predicted)
     */
    @Builder.Default
    private int successes = 0;

    /**
     * Number of failed outcomes (price moved opposite)
     */
    @Builder.Default
    private int failures = 0;

    /**
     * Number of expired outcomes (no clear result)
     */
    @Builder.Default
    private int expired = 0;

    // ======================== SUCCESS METRICS ========================

    /**
     * Success rate (successes / concluded)
     */
    public double getSuccessRate() {
        int concluded = successes + failures;
        if (concluded == 0) return 0;
        return (double) successes / concluded;
    }

    /**
     * Failure rate
     */
    public double getFailureRate() {
        int concluded = successes + failures;
        if (concluded == 0) return 0;
        return (double) failures / concluded;
    }

    /**
     * Expiry rate (patterns that didn't resolve)
     */
    public double getExpiryRate() {
        if (occurrences == 0) return 0;
        return (double) expired / occurrences;
    }

    // ======================== P&L METRICS ========================

    /**
     * Average gain on successful trades (percentage)
     */
    @Builder.Default
    private double avgGainOnSuccess = 0.0;

    /**
     * Average loss on failed trades (percentage)
     */
    @Builder.Default
    private double avgLossOnFailure = 0.0;

    /**
     * Maximum gain ever recorded (percentage)
     */
    @Builder.Default
    private double maxGain = 0.0;

    /**
     * Maximum loss ever recorded (percentage)
     */
    @Builder.Default
    private double maxLoss = 0.0;

    /**
     * Total cumulative P&L (percentage)
     */
    @Builder.Default
    private double totalPnlPct = 0.0;

    /**
     * Expected value per trade
     * EV = (successRate * avgGain) - (failureRate * avgLoss)
     */
    public double getExpectedValue() {
        return (getSuccessRate() * avgGainOnSuccess) - (getFailureRate() * Math.abs(avgLossOnFailure));
    }

    // ======================== TIMING METRICS ========================

    /**
     * Average time to outcome (milliseconds)
     */
    @Builder.Default
    private long avgTimeToOutcomeMs = 0;

    /**
     * Average time to target 1 (milliseconds)
     */
    @Builder.Default
    private long avgTimeToTarget1Ms = 0;

    /**
     * Percentage of trades that hit target 1
     */
    @Builder.Default
    private double target1HitRate = 0.0;

    /**
     * Percentage of trades that hit target 2
     */
    @Builder.Default
    private double target2HitRate = 0.0;

    // ======================== RISK METRICS ========================

    /**
     * Average R-multiple (profit in terms of risk units)
     */
    @Builder.Default
    private double avgRMultiple = 0.0;

    /**
     * Win/Loss ratio (avg win / avg loss)
     */
    public double getWinLossRatio() {
        if (avgLossOnFailure == 0) return avgGainOnSuccess > 0 ? Double.MAX_VALUE : 0;
        return avgGainOnSuccess / Math.abs(avgLossOnFailure);
    }

    /**
     * Profit factor (gross profit / gross loss)
     */
    @Builder.Default
    private double profitFactor = 0.0;

    /**
     * Maximum consecutive wins
     */
    @Builder.Default
    private int maxConsecutiveWins = 0;

    /**
     * Maximum consecutive losses
     */
    @Builder.Default
    private int maxConsecutiveLosses = 0;

    /**
     * Maximum drawdown (percentage)
     */
    @Builder.Default
    private double maxDrawdownPct = 0.0;

    // ======================== TIMESTAMPS ========================

    /**
     * First occurrence timestamp
     */
    private Instant firstOccurrence;

    /**
     * Last occurrence timestamp
     */
    private Instant lastOccurrence;

    /**
     * Last stats update timestamp
     */
    private Instant lastUpdated;

    // ======================== ROLLING STATS (Recent Performance) ========================

    /**
     * Success rate in last 20 trades
     */
    @Builder.Default
    private double recentSuccessRate = 0.0;

    /**
     * Expected value in last 20 trades
     */
    @Builder.Default
    private double recentExpectedValue = 0.0;

    /**
     * Is pattern performing better recently?
     */
    public boolean isImprovingRecently() {
        return recentSuccessRate > getSuccessRate();
    }

    // ======================== HELPER METHODS ========================

    /**
     * Check if we have sufficient data for reliable stats
     */
    public boolean hasSufficientData() {
        return occurrences >= 50 && (successes + failures) >= 30;
    }

    /**
     * Check if pattern is profitable
     */
    public boolean isProfitable() {
        return getExpectedValue() > 0;
    }

    /**
     * Check if pattern has edge (significantly positive EV)
     */
    public boolean hasEdge() {
        return getExpectedValue() > 0.1 && getSuccessRate() > 0.55;
    }

    /**
     * Get confidence multiplier based on sample size
     */
    public double getSampleSizeConfidence() {
        if (occurrences < 10) return 0.5;
        if (occurrences < 30) return 0.7;
        if (occurrences < 50) return 0.85;
        if (occurrences < 100) return 0.95;
        return 1.0;
    }

    /**
     * Update stats with a new outcome
     */
    public void recordOutcome(boolean success, double pnlPct, long timeToOutcomeMs) {
        occurrences++;

        if (success) {
            successes++;
            avgGainOnSuccess = updateAverage(avgGainOnSuccess, pnlPct, successes);
            maxGain = Math.max(maxGain, pnlPct);
        } else {
            failures++;
            avgLossOnFailure = updateAverage(avgLossOnFailure, pnlPct, failures);
            maxLoss = Math.min(maxLoss, pnlPct);
        }

        totalPnlPct += pnlPct;

        int concluded = successes + failures;
        avgTimeToOutcomeMs = updateAverageLong(avgTimeToOutcomeMs, timeToOutcomeMs, concluded);

        lastOccurrence = Instant.now();
        lastUpdated = Instant.now();

        if (firstOccurrence == null) {
            firstOccurrence = Instant.now();
        }
    }

    /**
     * Record an expired pattern
     */
    public void recordExpired() {
        occurrences++;
        expired++;
        lastOccurrence = Instant.now();
        lastUpdated = Instant.now();
    }

    private double updateAverage(double currentAvg, double newValue, int count) {
        if (count <= 1) return newValue;
        return currentAvg + (newValue - currentAvg) / count;
    }

    private long updateAverageLong(long currentAvg, long newValue, int count) {
        if (count <= 1) return newValue;
        return currentAvg + (newValue - currentAvg) / count;
    }

    /**
     * Create empty stats for a new pattern
     */
    public static HistoricalStats empty(String templateId) {
        return HistoricalStats.builder()
                .templateId(templateId)
                .occurrences(0)
                .successes(0)
                .failures(0)
                .expired(0)
                .lastUpdated(Instant.now())
                .build();
    }

    @Override
    public String toString() {
        return String.format("%s: %d trades, %.0f%% win rate, EV=%.2f%%, R=%.2f",
                templateId != null ? templateId : "Pattern",
                occurrences,
                getSuccessRate() * 100,
                getExpectedValue(),
                avgRMultiple);
    }
}
