package com.kotsin.consumer.enrichment.pattern.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * PatternSignal - Trading signal generated when a pattern completes
 *
 * This is published to Kafka 'trading-signals' topic and consumed by
 * tradeExecutionModule for trade execution and outcome tracking.
 *
 * Contains full pattern context for:
 * 1. Trade execution (entry, stop, targets)
 * 2. Signal reasoning (narrative, events matched)
 * 3. Outcome tracking (pattern ID for stats update)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatternSignal {

    // ======================== IDENTITY ========================

    /**
     * Unique signal ID
     */
    private String signalId;

    /**
     * Pattern template that generated this signal
     */
    private String patternId;

    /**
     * Active sequence ID (for outcome correlation)
     */
    private String sequenceId;

    /**
     * Family ID (e.g., "NATURALGAS", "NIFTY")
     */
    private String familyId;

    /**
     * Scrip code for trade execution (format: exchange:type:code)
     */
    private String scripCode;

    /**
     * Company/Instrument name
     */
    private String companyName;

    /**
     * Signal timestamp
     */
    private Instant timestamp;

    // ======================== SIGNAL TYPE ========================

    /**
     * Signal type (PATTERN, SETUP, TRIGGER)
     */
    @Builder.Default
    private String signalType = "PATTERN";

    /**
     * Pattern category (REVERSAL, CONTINUATION, BREAKOUT, etc.)
     */
    private String category;

    /**
     * Trading direction
     */
    private Direction direction;

    /**
     * Trading horizon
     */
    private Horizon horizon;

    public enum Direction {
        LONG, SHORT
    }

    public enum Horizon {
        SCALP, SWING, POSITIONAL
    }

    // ======================== TRADE PARAMETERS ========================

    /**
     * Recommended entry price
     */
    private double entryPrice;

    /**
     * Stop loss price
     */
    private double stopLoss;

    /**
     * Target 1 (partial exit)
     */
    private double target1;

    /**
     * Target 2 (full exit)
     */
    private double target2;

    /**
     * Target 3 (extended target)
     */
    private double target3;

    /**
     * Risk per share (entry - stop)
     */
    public double getRiskPerShare() {
        if (direction == Direction.LONG) {
            return entryPrice - stopLoss;
        } else {
            return stopLoss - entryPrice;
        }
    }

    /**
     * Risk/Reward ratio to target 2
     */
    public double getRiskRewardRatio() {
        double risk = getRiskPerShare();
        if (risk <= 0) return 0;

        double reward;
        if (direction == Direction.LONG) {
            reward = target2 - entryPrice;
        } else {
            reward = entryPrice - target2;
        }

        return reward / risk;
    }

    // ======================== CONFIDENCE ========================

    /**
     * Overall signal confidence (0-1)
     */
    private double confidence;

    /**
     * Pattern-based confidence
     */
    private double patternConfidence;

    /**
     * Historical success rate for this pattern
     */
    private double historicalSuccessRate;

    /**
     * Historical expected value
     */
    private double historicalExpectedValue;

    /**
     * Sample size (number of historical occurrences)
     */
    private int historicalSampleSize;

    // ======================== PATTERN CONTEXT ========================

    /**
     * Events that matched in this pattern
     */
    private List<String> matchedEvents;

    /**
     * Booster events that matched
     */
    private List<String> matchedBoosters;

    /**
     * Pattern completion percentage
     */
    private double patternProgress;

    /**
     * Time to pattern completion (ms)
     */
    private long patternDurationMs;

    /**
     * Price move during pattern formation
     */
    private double priceMoveDuringPattern;

    // ======================== MARKET CONTEXT ========================

    /**
     * GEX regime (TRENDING, MEAN_REVERTING)
     */
    private String gexRegime;

    /**
     * SuperTrend direction
     */
    private String superTrendDirection;

    /**
     * At confluence zone
     */
    private boolean atConfluenceZone;

    /**
     * Nearest support
     */
    private Double nearestSupport;

    /**
     * Nearest resistance
     */
    private Double nearestResistance;

    /**
     * Session (MORNING_TREND, LUNCH_CHOP, etc.)
     */
    private String session;

    /**
     * Days to expiry (for options)
     */
    private Integer daysToExpiry;

    // ======================== REASONING ========================

    /**
     * Human-readable narrative explaining the signal
     */
    private String narrative;

    /**
     * Key reasons for entry
     */
    private List<String> entryReasons;

    /**
     * Predicted follow-on events
     */
    private List<String> predictedEvents;

    /**
     * Conditions that would invalidate the trade
     */
    private List<String> invalidationWatch;

    // ======================== POSITION SIZING ========================

    /**
     * Recommended position size multiplier (0.5 - 1.5)
     */
    @Builder.Default
    private double positionSizeMultiplier = 1.0;

    /**
     * Maximum position size (shares)
     */
    private Integer maxPositionSize;

    /**
     * Risk percentage (of capital)
     */
    @Builder.Default
    private double riskPercentage = 1.5;

    // ======================== FLAGS ========================

    /**
     * Is this a high-confidence signal?
     */
    public boolean isHighConfidence() {
        return confidence >= 0.75;
    }

    /**
     * Is this a long signal?
     */
    public boolean isLongSignal() {
        return direction == Direction.LONG;
    }

    /**
     * Is this a short signal?
     */
    public boolean isShortSignal() {
        return direction == Direction.SHORT;
    }

    /**
     * Is actionable (meets minimum thresholds)?
     */
    public boolean isActionable() {
        return confidence >= 0.55
                && getRiskRewardRatio() >= 1.5
                && entryPrice > 0
                && stopLoss > 0
                && target1 > 0;
    }

    // ======================== METADATA ========================

    /**
     * Additional metadata for downstream processing
     */
    private Map<String, Object> metadata;

    // ======================== BUILDER HELPERS ========================

    /**
     * Set targets as percentages from entry
     */
    public void setTargetsFromPercentages(double target1Pct, double target2Pct, double target3Pct, double stopPct) {
        if (direction == Direction.LONG) {
            this.target1 = entryPrice * (1 + target1Pct / 100);
            this.target2 = entryPrice * (1 + target2Pct / 100);
            this.target3 = entryPrice * (1 + target3Pct / 100);
            this.stopLoss = entryPrice * (1 - stopPct / 100);
        } else {
            this.target1 = entryPrice * (1 - target1Pct / 100);
            this.target2 = entryPrice * (1 - target2Pct / 100);
            this.target3 = entryPrice * (1 - target3Pct / 100);
            this.stopLoss = entryPrice * (1 + stopPct / 100);
        }
    }

    @Override
    public String toString() {
        return String.format("PatternSignal[%s] %s %s %s @ %.2f (SL: %.2f, T1: %.2f, T2: %.2f) conf=%.0f%%",
                signalId != null ? signalId.substring(0, 8) : "???",
                patternId,
                direction,
                familyId,
                entryPrice,
                stopLoss,
                target1,
                target2,
                confidence * 100);
    }
}
