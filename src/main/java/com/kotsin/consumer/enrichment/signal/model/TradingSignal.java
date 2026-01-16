package com.kotsin.consumer.enrichment.signal.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TradingSignal - Enhanced trading signal with complete context
 *
 * Layer 8: Signal Generator output combining:
 * - Standard: entry, stop, targets, confidence
 * - NEW: narrative (why this signal?)
 * - NEW: sequence_basis (which pattern?)
 * - NEW: predicted_events (what should happen next?)
 * - NEW: invalidation_watch (what to monitor?)
 *
 * This is the final output consumed by trade execution systems.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradingSignal {

    // ======================== IDENTITY ========================

    /**
     * Unique signal ID
     */
    @Builder.Default
    private String signalId = UUID.randomUUID().toString();

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
     * Exchange code: "N" for NSE, "M" for MCX, "B" for BSE
     * FIX: Added to properly propagate exchange info for MCX instruments
     */
    private String exchange;

    /**
     * Signal generation timestamp - when this signal was created by the system
     * FIX: This should always be Instant.now() for staleness checking
     */
    @Builder.Default
    private Instant generatedAt = Instant.now();

    /**
     * FIX: Market data timestamp - the time of the candle/price data used for this signal
     * This separates "when was signal generated" from "what time is the market data from"
     * Critical for accurate price-time correlation when displaying signals
     */
    private Instant dataTimestamp;

    /**
     * Signal expiry timestamp
     */
    private Instant expiresAt;

    /**
     * Human readable signal time in IST (e.g., "11 Jan 2026 12:45:33 IST")
     * FIX: This should reflect dataTimestamp (market data time), not generatedAt
     */
    private String humanReadableTime;

    /**
     * Human readable entry time in IST for display (e.g., "12:45 PM")
     */
    private String entryTimeIST;

    // ======================== SIGNAL TYPE ========================

    /**
     * Source that generated this signal
     */
    private SignalSource source;

    /**
     * Signal category
     */
    private SignalCategory category;

    /**
     * Trading direction
     */
    private Direction direction;

    /**
     * Trading horizon
     */
    private Horizon horizon;

    /**
     * Signal urgency
     */
    @Builder.Default
    private Urgency urgency = Urgency.NORMAL;

    public enum SignalSource {
        PATTERN,        // From pattern recognition (Phase 4)
        SETUP,          // From setup tracker (Phase 5)
        FORECAST,       // From opportunity forecaster (Phase 5)
        INTELLIGENCE,   // From combined intelligence
        MANUAL          // Manual/override signal
    }

    public enum SignalCategory {
        REVERSAL,       // Price direction change
        CONTINUATION,   // Trend continuation
        BREAKOUT,       // Breaking resistance
        BREAKDOWN,      // Breaking support
        MEAN_REVERSION, // Return to mean
        MOMENTUM,       // Following momentum
        SQUEEZE,        // Gamma/volatility squeeze
        EXHAUSTION      // Exhaustion trade
    }

    public enum Direction {
        LONG, SHORT
    }

    public enum Horizon {
        SCALP,          // < 30 minutes
        INTRADAY,       // < 1 day
        SWING,          // 1-5 days
        POSITIONAL      // > 5 days
    }

    public enum Urgency {
        IMMEDIATE,      // Act now
        NORMAL,         // Normal priority
        WAIT_FOR_ENTRY, // Wait for specific entry
        INFORMATIONAL   // For awareness only
    }

    // ======================== TRADE PARAMETERS ========================

    /**
     * Current market price
     */
    private double currentPrice;

    /**
     * Recommended entry price
     */
    private double entryPrice;

    /**
     * Entry type (market, limit, stop-limit)
     */
    @Builder.Default
    private EntryType entryType = EntryType.LIMIT;

    /**
     * Stop loss price
     */
    private double stopLoss;

    /**
     * Target 1 (conservative - partial exit)
     */
    private double target1;

    /**
     * Target 2 (primary target)
     */
    private double target2;

    /**
     * Target 3 (extended target)
     */
    private double target3;

    /**
     * Trailing stop activation price
     */
    private Double trailingStopActivation;

    /**
     * Trailing stop distance percentage
     */
    private Double trailingStopPct;

    public enum EntryType {
        MARKET,         // Market order
        LIMIT,          // Limit order
        STOP_LIMIT,     // Stop-limit order
        SCALE_IN        // Multiple entries
    }

    // ======================== CONFIDENCE & SIZING ========================

    /**
     * Overall signal confidence (0-1)
     */
    private double confidence;

    /**
     * Breakdown of confidence sources
     */
    private ConfidenceBreakdown confidenceBreakdown;

    /**
     * Recommended position size multiplier (0.5 - 2.0)
     */
    @Builder.Default
    private double positionSizeMultiplier = 1.0;

    /**
     * Risk per trade as percentage of capital
     */
    @Builder.Default
    private double riskPercentage = 1.0;

    /**
     * Maximum position size in lots/shares
     */
    private Integer maxPositionSize;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConfidenceBreakdown {
        private double patternConfidence;       // From pattern matching
        private double setupConfidence;         // From setup tracker
        private double technicalConfidence;     // From technical indicators
        private double quantScoreConfidence;    // From quant score
        private double historicalConfidence;    // From historical success rate
        private double contextConfidence;       // From market context

        public double getWeightedAverage() {
            double total = patternConfidence + setupConfidence + technicalConfidence +
                    quantScoreConfidence + historicalConfidence + contextConfidence;
            return total / 6.0;
        }
    }

    // ======================== NARRATIVE (NEW) ========================

    /**
     * Complete signal rationale
     */
    private SignalRationale rationale;

    /**
     * One-line headline
     */
    private String headline;

    /**
     * Full narrative explaining the signal
     */
    private String narrative;

    /**
     * Key reasons for taking this trade
     */
    @Builder.Default
    private List<String> entryReasons = new ArrayList<>();

    /**
     * What makes this opportunity unique
     */
    private String edgeDescription;

    // ======================== SEQUENCE BASIS (NEW) ========================

    /**
     * Pattern ID that triggered this signal
     */
    private String patternId;

    /**
     * Active sequence ID for tracking
     */
    private String sequenceId;

    /**
     * Setup ID if from setup tracker
     */
    private String setupId;

    /**
     * Events that matched in the pattern/setup
     */
    @Builder.Default
    private List<String> matchedEvents = new ArrayList<>();

    /**
     * Booster conditions that were met
     */
    @Builder.Default
    private List<String> matchedBoosters = new ArrayList<>();

    /**
     * Pattern completion percentage
     */
    private double patternProgress;

    /**
     * Historical success rate for this pattern/setup
     */
    private double historicalSuccessRate;

    /**
     * Number of historical samples
     */
    private int historicalSampleCount;

    /**
     * Historical expected value (avg return)
     */
    private double historicalExpectedValue;

    // ======================== PREDICTED EVENTS (NEW) ========================

    /**
     * List of predicted events that should follow
     */
    @Builder.Default
    private List<PredictedFollowOn> predictedEvents = new ArrayList<>();

    /**
     * Expected price action sequence
     */
    private String expectedPriceAction;

    /**
     * Expected volatility pattern
     */
    private String expectedVolatility;

    /**
     * Key levels to watch
     */
    @Builder.Default
    private List<KeyLevel> keyLevels = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PredictedFollowOn {
        private String event;           // What should happen
        private String timeframe;       // When (e.g., "15-30 minutes")
        private double probability;     // How likely (0-1)
        private String confirmation;    // What would confirm it
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KeyLevel {
        private double price;
        private String type;            // SUPPORT, RESISTANCE, PIVOT, MAX_PAIN, GEX_FLIP
        private String significance;    // Why it matters
        private double strength;        // Level strength (0-1)
    }

    // ======================== INVALIDATION WATCH (NEW) ========================

    /**
     * Conditions that would invalidate this signal
     */
    @Builder.Default
    private List<InvalidationCondition> invalidationWatch = new ArrayList<>();

    /**
     * Primary invalidation price (below/above which signal is void)
     */
    private Double invalidationPrice;

    /**
     * Time-based invalidation
     */
    private Instant invalidationTime;

    /**
     * Events that would invalidate
     */
    @Builder.Default
    private List<String> invalidationEvents = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvalidationCondition {
        private String condition;       // Description
        private String monitorType;     // PRICE, TIME, EVENT, VOLUME
        private Double threshold;       // Numeric threshold if applicable
        private String action;          // What to do if triggered (EXIT, REDUCE, ALERT)
    }

    // ======================== MARKET CONTEXT ========================

    /**
     * GEX regime at signal generation
     */
    private String gexRegime;

    /**
     * SuperTrend direction
     */
    private String superTrendDirection;

    /**
     * In confluence zone
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
     * Current session
     */
    private String session;

    /**
     * Days to expiry (for options)
     */
    private Integer daysToExpiry;

    /**
     * Max pain level
     */
    private Double maxPainLevel;

    /**
     * Gamma flip level
     */
    private Double gammaFlipLevel;

    // ======================== RISK METRICS ========================

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
     * Risk percentage from entry
     */
    public double getRiskPct() {
        if (entryPrice <= 0) return 0;
        return getRiskPerShare() / entryPrice * 100;
    }

    /**
     * Reward to target 2
     */
    public double getRewardToTarget2() {
        if (direction == Direction.LONG) {
            return target2 - entryPrice;
        } else {
            return entryPrice - target2;
        }
    }

    /**
     * Risk/Reward ratio to target 2
     */
    public double getRiskRewardRatio() {
        double risk = getRiskPerShare();
        if (risk <= 0) return 0;
        return getRewardToTarget2() / risk;
    }

    /**
     * Expected value calculation
     */
    public double getExpectedValue() {
        double winRate = historicalSuccessRate > 0 ? historicalSuccessRate : confidence;
        double avgWin = getRewardToTarget2();
        double avgLoss = getRiskPerShare();
        return (winRate * avgWin) - ((1 - winRate) * avgLoss);
    }

    // ======================== SIGNAL QUALITY ========================

    /**
     * Check if signal is high quality
     */
    public boolean isHighQuality() {
        return confidence >= 0.70 &&
                getRiskRewardRatio() >= 2.0 &&
                historicalSuccessRate >= 0.55 &&
                !invalidationWatch.isEmpty();
    }

    /**
     * Check if signal is actionable
     */
    public boolean isActionable() {
        return confidence >= 0.45 && // Lowered from 0.55
                getRiskRewardRatio() >= 1.2 && // Lowered from 1.5
                entryPrice > 0 &&
                stopLoss > 0 &&
                target1 > 0 &&
                urgency != Urgency.INFORMATIONAL;
    }

    /**
     * Check if signal is immediate priority
     */
    public boolean isImmediatePriority() {
        return urgency == Urgency.IMMEDIATE && isActionable();
    }

    /**
     * Calculate signal quality score (0-100)
     */
    public int getQualityScore() {
        int score = 0;

        // Confidence (max 25 points)
        score += (int) (confidence * 25);

        // Risk/Reward (max 25 points)
        double rr = Math.min(getRiskRewardRatio(), 4.0);
        score += (int) (rr / 4.0 * 25);

        // Historical success (max 25 points)
        score += (int) (historicalSuccessRate * 25);

        // Completeness (max 25 points)
        int completenessScore = 0;
        if (!entryReasons.isEmpty()) completenessScore += 5;
        if (!predictedEvents.isEmpty()) completenessScore += 5;
        if (!invalidationWatch.isEmpty()) completenessScore += 5;
        if (rationale != null) completenessScore += 5;
        if (!matchedEvents.isEmpty()) completenessScore += 5;
        score += completenessScore;

        return Math.min(100, score);
    }

    // ======================== SIGNAL STATUS ========================

    /**
     * Signal status
     */
    @Builder.Default
    private SignalStatus status = SignalStatus.ACTIVE;

    /**
     * When signal was triggered
     */
    private Instant triggeredAt;

    /**
     * Actual entry price
     */
    private Double actualEntryPrice;

    /**
     * Exit price (if closed)
     */
    private Double exitPrice;

    /**
     * Exit reason
     */
    private String exitReason;

    /**
     * Profit/Loss amount
     */
    private Double pnl;

    /**
     * Profit/Loss percentage
     */
    private Double pnlPct;

    public enum SignalStatus {
        ACTIVE,         // Signal is active, awaiting trigger
        TRIGGERED,      // Trade was entered
        PARTIAL_TARGET, // Hit partial target
        TARGET_HIT,     // Hit primary target
        STOPPED_OUT,    // Hit stop loss
        INVALIDATED,    // Invalidation condition triggered
        EXPIRED,        // Signal expired
        CANCELLED       // Signal cancelled
    }

    // ======================== METADATA ========================

    /**
     * Additional metadata for downstream processing
     */
    private Map<String, Object> metadata;

    /**
     * Processing version
     */
    @Builder.Default
    private String version = "2.0";

    // ======================== HELPER METHODS ========================

    /**
     * Check if signal has expired
     */
    public boolean hasExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /**
     * Check if this is a long signal
     */
    public boolean isLong() {
        return direction == Direction.LONG;
    }

    /**
     * Check if this is a short signal
     */
    public boolean isShort() {
        return direction == Direction.SHORT;
    }

    /**
     * Get direction as multiplier (1 for long, -1 for short)
     */
    public int getDirectionMultiplier() {
        return direction == Direction.LONG ? 1 : -1;
    }

    /**
     * Get time remaining in minutes
     */
    public long getTimeRemainingMinutes() {
        if (expiresAt == null) return -1;
        long remaining = (expiresAt.toEpochMilli() - Instant.now().toEpochMilli()) / 60000;
        return Math.max(0, remaining);
    }

    /**
     * Get signal summary for logging
     */
    public String getSummary() {
        return String.format("%s %s %s @ %.2f (SL: %.2f, T: %.2f) R:R=%.1f Conf=%.0f%%",
                direction, category, familyId,
                entryPrice, stopLoss, target2,
                getRiskRewardRatio(), confidence * 100);
    }

    @Override
    public String toString() {
        return String.format("TradingSignal[%s] %s %s %s @ %.2f (R:R=%.1f, Conf=%.0f%%, Quality=%d)",
                signalId.substring(0, 8),
                direction, category, familyId,
                entryPrice, getRiskRewardRatio(),
                confidence * 100, getQualityScore());
    }
}
