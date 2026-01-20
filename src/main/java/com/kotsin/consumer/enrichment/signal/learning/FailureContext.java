package com.kotsin.consumer.enrichment.signal.learning;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * FailureContext - Captures FULL context when a trade hits stop loss
 *
 * PURPOSE:
 * When SL is hit, we need to understand WHY to learn from it.
 * This model captures everything about the trade at entry and exit.
 *
 * LEARNING GOALS:
 * 1. Identify patterns in failures (same scrip, same conditions)
 * 2. Auto-generate avoidance rules per scrip
 * 3. Continuously improve signal quality
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailureContext {

    // ======================== SIGNAL IDENTIFICATION ========================
    private String signalId;
    private String familyId;
    private String scripCode;
    private String companyName;
    private String direction;           // LONG or SHORT
    private String setupType;           // Setup ID that generated signal
    private String signalSource;        // SETUP, PATTERN, FORECAST

    // ======================== ENTRY CONTEXT ========================
    private double entryPrice;
    private double stopLoss;
    private double target1;
    private double target2;
    private double entryRiskReward;     // R:R at entry time
    private Instant entryTime;

    // ======================== CONFIRMATION SCORES AT ENTRY ========================
    /**
     * Price action confirmation score (0-100) at entry
     */
    private int confirmationScoreAtEntry;
    private int retestScoreAtEntry;
    private int ofiScoreAtEntry;
    private int optionsScoreAtEntry;
    private int sessionScoreAtEntry;
    private int mtfScoreAtEntry;
    private int technicalScoreAtEntry;

    // ======================== MARKET CONTEXT AT ENTRY ========================
    /**
     * OI interpretation at entry time
     * LONG_BUILDUP, SHORT_COVERING, SHORT_BUILDUP, LONG_UNWINDING
     */
    private String oiInterpretationAtEntry;
    private Double oiConfidenceAtEntry;

    /**
     * Order flow metrics at entry
     */
    private Double ofiAtEntry;
    private Double ofiVelocityAtEntry;
    private Double ofiAccelerationAtEntry;

    /**
     * Technical alignment at entry
     */
    private boolean superTrendAlignedAtEntry;
    private Double bbPercentBAtEntry;

    /**
     * Session structure at entry
     */
    private double sessionPositionAtEntry;     // 0.0 = at low, 1.0 = at high
    private String sessionPositionDescAtEntry; // AT_SESSION_LOW, MIDDLE, AT_SESSION_HIGH

    /**
     * GEX regime at entry
     */
    private String gexRegimeAtEntry;

    /**
     * Family bias at entry
     */
    private String familyBiasAtEntry;
    private Double familyAlignmentAtEntry;

    /**
     * Events detected at entry time
     */
    private List<String> eventsAtEntry;

    // ======================== EXIT CONTEXT (SL HIT) ========================
    private double exitPrice;
    private Instant exitTime;
    private long timeToSLMillis;         // How quickly did it fail?

    /**
     * Max Adverse Excursion - How far price went against us before SL
     * For LONG: (entryPrice - lowestPrice) / entryPrice * 100
     * For SHORT: (highestPrice - entryPrice) / entryPrice * 100
     */
    private double maxAdverseExcursionPct;

    /**
     * Max Favorable Excursion - Did price ever go profitable?
     * For LONG: (highestPrice - entryPrice) / entryPrice * 100
     * For SHORT: (entryPrice - lowestPrice) / entryPrice * 100
     */
    private double maxFavorableExcursionPct;

    /**
     * Was trade ever profitable before SL hit?
     */
    private boolean wasEverProfitable;

    /**
     * If profitable, what was max profit before it reversed?
     */
    private double maxProfitBeforeReversalPct;

    /**
     * Events that occurred between entry and SL hit
     */
    private List<String> eventsBetweenEntryAndSL;

    // ======================== FAILURE CLASSIFICATION ========================
    /**
     * Primary failure type - auto-classified based on behavior
     */
    private FailureType failureType;

    /**
     * Secondary failure indicators
     */
    private List<String> failureIndicators;

    /**
     * Auto-generated learning tags for pattern recognition
     * e.g., ["OI_DIVERGENCE", "SESSION_HIGH_LONG", "WEAK_OFI"]
     */
    private List<String> learningTags;

    // ======================== CLASSIFICATION LOGIC ========================

    /**
     * Failure type classification
     */
    public enum FailureType {
        /**
         * SL hit within 5 candles, never went profitable
         * Indicates: Entry was at wrong level/timing
         */
        IMMEDIATE_REVERSAL,

        /**
         * Slow drift to SL over many candles
         * Indicates: Momentum faded, trend exhausted
         */
        GRADUAL_DECAY,

        /**
         * Was profitable, then reversed sharply to SL
         * Indicates: Should have taken partial profits or trailed SL
         */
        PROFIT_REVERSAL,

        /**
         * SL hit on gap open
         * Indicates: Overnight risk, news event
         */
        GAP_THROUGH,

        /**
         * Sudden volatility spike pierced SL
         * Indicates: SL too tight for market conditions
         */
        VOLATILITY_SPIKE,

        /**
         * SL hit during first 30 minutes
         * Indicates: Opening range volatility
         */
        OPENING_VOLATILITY,

        /**
         * SL hit in last hour
         * Indicates: End of day squeeze
         */
        EOD_REVERSAL,

        /**
         * OI was diverging from price direction at entry
         * Indicates: Ignored OI warning
         */
        OI_DIVERGENCE_IGNORED,

        /**
         * Entered at session extreme (LONG at high, SHORT at low)
         * Indicates: Ignored session context
         */
        SESSION_TRAP,

        /**
         * Breakout failed and reversed
         * Indicates: False breakout
         */
        FALSE_BREAKOUT,

        /**
         * Had exhaustion signal but took trade anyway
         * Indicates: Ignored exhaustion warning
         */
        EXHAUSTION_IGNORED,

        /**
         * SuperTrend was opposite to trade direction
         * Indicates: Counter-trend trade failed
         */
        COUNTER_TREND_FAILED,

        /**
         * Multiple contradictory signals at entry
         * Indicates: Low conviction entry
         */
        LOW_CONVICTION_ENTRY,

        /**
         * Unknown/unclassified failure
         */
        UNKNOWN
    }

    // ======================== HELPER METHODS ========================

    /**
     * Calculate time to SL in minutes
     */
    public long getTimeToSLMinutes() {
        return timeToSLMillis / 60000;
    }

    /**
     * Was this a quick failure (< 15 minutes)?
     */
    public boolean isQuickFailure() {
        return getTimeToSLMinutes() < 15;
    }

    /**
     * Was this a prolonged failure (> 60 minutes)?
     */
    public boolean isProlongedFailure() {
        return getTimeToSLMinutes() > 60;
    }

    /**
     * Calculate R-multiple of the loss
     * Should be close to -1R if SL was hit exactly
     */
    public double getRMultiple() {
        double riskPerShare = Math.abs(entryPrice - stopLoss);
        if (riskPerShare == 0) return 0;

        double pnlPerShare = "LONG".equals(direction) ?
                exitPrice - entryPrice :
                entryPrice - exitPrice;

        return pnlPerShare / riskPerShare;
    }

    /**
     * Get summary string for logging
     */
    public String getSummary() {
        return String.format("%s %s %s | Type=%s | TimeToSL=%dm | MAE=%.2f%% | MFE=%.2f%% | Tags=%s",
                scripCode, direction, setupType,
                failureType,
                getTimeToSLMinutes(),
                maxAdverseExcursionPct,
                maxFavorableExcursionPct,
                learningTags != null ? String.join(",", learningTags) : "none");
    }
}
