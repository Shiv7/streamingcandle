package com.kotsin.consumer.enrichment.calculator;

import com.kotsin.consumer.enrichment.model.MetricContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ThresholdCalculator - Adaptive percentile-based thresholds for SMTIS
 *
 * REPLACES hardcoded thresholds with instrument-relative thresholds.
 *
 * Before (hardcoded):
 *   if (oiChange > 1000) -> SIGNAL
 *   Problem: NATURALGAS OI=4000 vs NIFTY OI=10M treated the same
 *
 * After (adaptive):
 *   if (percentile > 90) -> SIGNAL
 *   NATURALGAS: +200 OI (5%) -> percentile 95 -> SIGNAL
 *   NIFTY: +1000 OI (0.01%) -> percentile 30 -> NO SIGNAL
 *
 * Key Features:
 * - All thresholds based on percentile ranking
 * - Z-score for extreme value detection
 * - Supports asymmetric thresholds (different for buy/sell)
 * - Commodity-specific adjustments
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ThresholdCalculator {

    // Default threshold percentiles
    public static final double STRONG_SIGNAL_PERCENTILE = 90.0;
    public static final double NORMAL_SIGNAL_PERCENTILE = 75.0;
    public static final double WEAK_SIGNAL_PERCENTILE = 60.0;
    public static final double NEUTRAL_MIN_PERCENTILE = 40.0;
    public static final double NEUTRAL_MAX_PERCENTILE = 60.0;

    // Z-score thresholds
    public static final double EXTREME_ZSCORE = 2.0;
    public static final double HIGH_ZSCORE = 1.5;
    public static final double NORMAL_ZSCORE = 1.0;

    /**
     * Check if a metric triggers a signal based on adaptive threshold
     *
     * @param context MetricContext with statistics
     * @param thresholdType Type of threshold to check
     * @return ThresholdResult with signal details
     */
    public ThresholdResult checkThreshold(MetricContext context, ThresholdType thresholdType) {
        if (context == null || context.isInLearningMode()) {
            return ThresholdResult.learningMode();
        }

        double percentile = context.getPercentile();
        double zscore = context.getZscore();
        boolean isPositive = context.getCurrentValue() > 0;

        return switch (thresholdType) {
            case STRONG_BULLISH -> checkStrongBullish(percentile, zscore, isPositive);
            case STRONG_BEARISH -> checkStrongBearish(percentile, zscore, isPositive);
            case BULLISH -> checkBullish(percentile, zscore, isPositive);
            case BEARISH -> checkBearish(percentile, zscore, isPositive);
            case EXTREME -> checkExtreme(percentile, zscore);
            case NEUTRAL -> checkNeutral(percentile);
        };
    }

    /**
     * Get signal strength based on percentile
     *
     * @param context MetricContext
     * @return Signal strength [0.0, 1.0]
     */
    public double getSignalStrength(MetricContext context) {
        if (context == null || context.isInLearningMode()) {
            return 0.0;
        }

        double percentile = context.getPercentile();
        double zscore = Math.abs(context.getZscore());

        // Combine percentile and z-score for signal strength
        double percentileStrength = 0.0;
        if (percentile >= STRONG_SIGNAL_PERCENTILE || percentile <= (100 - STRONG_SIGNAL_PERCENTILE)) {
            percentileStrength = 1.0;
        } else if (percentile >= NORMAL_SIGNAL_PERCENTILE || percentile <= (100 - NORMAL_SIGNAL_PERCENTILE)) {
            percentileStrength = 0.7;
        } else if (percentile >= WEAK_SIGNAL_PERCENTILE || percentile <= (100 - WEAK_SIGNAL_PERCENTILE)) {
            percentileStrength = 0.4;
        }

        double zscoreStrength = Math.min(zscore / EXTREME_ZSCORE, 1.0);

        // Weighted average: 60% percentile, 40% z-score
        return percentileStrength * 0.6 + zscoreStrength * 0.4;
    }

    /**
     * Get confidence modifier based on data quality
     *
     * @param context MetricContext
     * @return Confidence modifier [0.5, 1.0]
     */
    public double getConfidenceModifier(MetricContext context) {
        if (context == null || context.isInLearningMode()) {
            return 0.5; // Reduced confidence in learning mode
        }

        // Higher confidence when:
        // 1. More samples available
        // 2. Consistent regime
        // 3. Recent flip (actionable moment)

        double sampleConfidence = Math.min(context.getSamplesCollected() / 20.0, 1.0);
        double regimeConfidence = context.getConfidenceModifier();
        double flipBoost = context.isFlipDetected() ? 1.1 : 1.0;

        return Math.min(sampleConfidence * regimeConfidence * flipBoost, 1.0);
    }

    /**
     * Calculate adaptive OI threshold for commodities vs equities
     *
     * For commodities (MCX): Use lower percentile thresholds
     * For equities (NSE): Use standard percentile thresholds
     *
     * @param context MetricContext
     * @param isCommodity Whether this is a commodity
     * @return Adjusted threshold percentile
     */
    public double getAdaptiveOIThreshold(MetricContext context, boolean isCommodity) {
        // Commodities have lower liquidity, so lower thresholds
        double baseThreshold = isCommodity ?
                NORMAL_SIGNAL_PERCENTILE - 10.0 : // 65th percentile for commodities
                NORMAL_SIGNAL_PERCENTILE;         // 75th percentile for equities

        // Adjust based on historical volatility (stddev relative to mean)
        if (context != null && context.hasEnoughSamples() && context.getMean() != 0) {
            double cv = Math.abs(context.getStddev() / context.getMean()); // Coefficient of variation

            if (cv > 2.0) {
                // High volatility: use stricter threshold (avoid noise)
                baseThreshold += 5.0;
            } else if (cv < 0.5) {
                // Low volatility: use relaxed threshold (capture smaller moves)
                baseThreshold -= 5.0;
            }
        }

        return Math.max(50.0, Math.min(95.0, baseThreshold)); // Clamp to reasonable range
    }

    /**
     * Check if flip is significant enough to generate signal
     */
    public boolean isSignificantFlip(MetricContext context) {
        if (context == null || !context.isFlipDetected()) {
            return false;
        }

        // Significant if flip magnitude > 1.5 stddev
        return context.getFlipZscore() > HIGH_ZSCORE;
    }

    // ======================== PRIVATE METHODS ========================

    private ThresholdResult checkStrongBullish(double percentile, double zscore, boolean isPositive) {
        if (!isPositive) {
            return ThresholdResult.notTriggered();
        }

        boolean triggered = percentile >= STRONG_SIGNAL_PERCENTILE && zscore >= HIGH_ZSCORE;
        return new ThresholdResult(
                triggered,
                triggered ? SignalStrength.STRONG : SignalStrength.NONE,
                percentile,
                zscore,
                false
        );
    }

    private ThresholdResult checkStrongBearish(double percentile, double zscore, boolean isPositive) {
        if (isPositive) {
            return ThresholdResult.notTriggered();
        }

        boolean triggered = percentile <= (100 - STRONG_SIGNAL_PERCENTILE) && zscore <= -HIGH_ZSCORE;
        return new ThresholdResult(
                triggered,
                triggered ? SignalStrength.STRONG : SignalStrength.NONE,
                percentile,
                zscore,
                false
        );
    }

    private ThresholdResult checkBullish(double percentile, double zscore, boolean isPositive) {
        if (!isPositive) {
            return ThresholdResult.notTriggered();
        }

        SignalStrength strength = SignalStrength.NONE;
        boolean triggered = false;

        if (percentile >= STRONG_SIGNAL_PERCENTILE) {
            triggered = true;
            strength = SignalStrength.STRONG;
        } else if (percentile >= NORMAL_SIGNAL_PERCENTILE) {
            triggered = true;
            strength = SignalStrength.NORMAL;
        } else if (percentile >= WEAK_SIGNAL_PERCENTILE) {
            triggered = true;
            strength = SignalStrength.WEAK;
        }

        return new ThresholdResult(triggered, strength, percentile, zscore, false);
    }

    private ThresholdResult checkBearish(double percentile, double zscore, boolean isPositive) {
        if (isPositive) {
            return ThresholdResult.notTriggered();
        }

        SignalStrength strength = SignalStrength.NONE;
        boolean triggered = false;

        if (percentile <= (100 - STRONG_SIGNAL_PERCENTILE)) {
            triggered = true;
            strength = SignalStrength.STRONG;
        } else if (percentile <= (100 - NORMAL_SIGNAL_PERCENTILE)) {
            triggered = true;
            strength = SignalStrength.NORMAL;
        } else if (percentile <= (100 - WEAK_SIGNAL_PERCENTILE)) {
            triggered = true;
            strength = SignalStrength.WEAK;
        }

        return new ThresholdResult(triggered, strength, percentile, zscore, false);
    }

    private ThresholdResult checkExtreme(double percentile, double zscore) {
        boolean triggered = percentile >= 95.0 || percentile <= 5.0 || Math.abs(zscore) >= EXTREME_ZSCORE;
        SignalStrength strength = triggered ? SignalStrength.EXTREME : SignalStrength.NONE;
        return new ThresholdResult(triggered, strength, percentile, zscore, false);
    }

    private ThresholdResult checkNeutral(double percentile) {
        boolean triggered = percentile >= NEUTRAL_MIN_PERCENTILE && percentile <= NEUTRAL_MAX_PERCENTILE;
        return new ThresholdResult(triggered, SignalStrength.NONE, percentile, 0, false);
    }

    // ======================== ENUMS AND RECORDS ========================

    public enum ThresholdType {
        STRONG_BULLISH,
        STRONG_BEARISH,
        BULLISH,
        BEARISH,
        EXTREME,
        NEUTRAL
    }

    public enum SignalStrength {
        EXTREME,
        STRONG,
        NORMAL,
        WEAK,
        NONE
    }

    public record ThresholdResult(
            boolean triggered,
            SignalStrength strength,
            double percentile,
            double zscore,
            boolean inLearningMode
    ) {
        public static ThresholdResult notTriggered() {
            return new ThresholdResult(false, SignalStrength.NONE, 50, 0, false);
        }

        public static ThresholdResult learningMode() {
            return new ThresholdResult(false, SignalStrength.NONE, 50, 0, true);
        }

        public double getConfidenceBoost() {
            return switch (strength) {
                case EXTREME -> 1.3;
                case STRONG -> 1.2;
                case NORMAL -> 1.0;
                case WEAK -> 0.8;
                case NONE -> 0.5;
            };
        }
    }
}
