package com.kotsin.consumer.enrichment.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * HistoricalContext - Aggregates all metric contexts for a FamilyCandle
 *
 * This is the primary output of Phase 1 enrichment.
 * Contains historical context for all key metrics + derived signals.
 *
 * Metrics Tracked:
 * 1. OFI (Order Flow Imbalance) - Who's aggressive
 * 2. Volume Delta - Net buyer/seller pressure
 * 3. OI Change - Open Interest movement
 * 4. OI Velocity - Rate of OI change
 * 5. Kyle's Lambda - Price impact / liquidity
 * 6. VPIN - Informed vs uninformed trading
 * 7. Depth Imbalance - Book shape
 * 8. Buy Pressure - Trade classification
 *
 * Derived Signals:
 * - Absorption Detection (Lambda + Depth)
 * - Liquidity Withdrawal (Lambda + Spread)
 * - Informed Flow Active (VPIN)
 * - Exhaustion Detection (OFI velocity)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoricalContext {

    // ======================== IDENTITY ========================

    private String familyId;
    private String timeframe;
    private Instant timestamp;
    private long timestampMillis;

    // ======================== METRIC CONTEXTS ========================

    private MetricContext ofiContext;
    private MetricContext volumeDeltaContext;
    private MetricContext oiChangeContext;
    private MetricContext oiVelocityContext;
    private MetricContext lambdaContext;
    private MetricContext vpinContext;
    private MetricContext depthImbalanceContext;
    private MetricContext buyPressureContext;
    private MetricContext spreadContext;

    // ======================== DERIVED SIGNALS ========================

    /**
     * Absorption detected when large orders absorbed without price impact
     * Conditions: Low lambda + depth imbalance + OFI positive
     */
    private boolean absorptionDetected;
    private String absorptionType; // BULLISH_ACCUMULATION, BEARISH_DISTRIBUTION

    /**
     * Liquidity withdrawal - market makers stepping away
     * Conditions: High lambda + widening spread + declining depth
     */
    private boolean liquidityWithdrawal;

    /**
     * Informed flow active (smart money)
     * Conditions: VPIN > 0.7
     */
    private boolean informedFlowActive;
    private double informedFlowIntensity;

    /**
     * Selling/Buying exhaustion
     * Conditions: OFI negative but velocity turning positive
     */
    private boolean sellingExhaustion;
    private boolean buyingExhaustion;

    /**
     * Momentum building
     * Conditions: OFI accelerating in same direction
     */
    private boolean momentumBuilding;
    private String momentumDirection; // BULLISH, BEARISH

    // ======================== FLIP SUMMARY ========================

    /**
     * Count of flips detected across all metrics
     */
    private int totalFlipsDetected;

    /**
     * Most significant flip
     */
    private String significantFlipMetric;
    private MetricContext.FlipType significantFlipType;
    private double significantFlipZscore;

    // ======================== LEARNING MODE ========================

    /**
     * Percentage of metrics with sufficient data
     */
    private double dataCompleteness;

    /**
     * True if any critical metric is in learning mode
     */
    private boolean inLearningMode;

    /**
     * List of metrics still in learning mode
     */
    private List<String> learningModeMetrics;

    // ======================== CONFIDENCE ========================

    /**
     * Overall historical context confidence
     * Based on data completeness and regime consistency
     */
    private double historicalConfidence;

    // ======================== HELPER METHODS ========================

    /**
     * Check if any bullish flip detected
     */
    public boolean hasBullishFlip() {
        return hasFlipType(MetricContext.FlipType.BEARISH_TO_BULLISH)
                || hasFlipType(MetricContext.FlipType.NEUTRAL_TO_BULLISH);
    }

    /**
     * Check if any bearish flip detected
     */
    public boolean hasBearishFlip() {
        return hasFlipType(MetricContext.FlipType.BULLISH_TO_BEARISH)
                || hasFlipType(MetricContext.FlipType.NEUTRAL_TO_BEARISH);
    }

    /**
     * Check if specific flip type detected in any metric
     */
    public boolean hasFlipType(MetricContext.FlipType flipType) {
        return checkFlipType(ofiContext, flipType)
                || checkFlipType(volumeDeltaContext, flipType)
                || checkFlipType(oiChangeContext, flipType);
    }

    private boolean checkFlipType(MetricContext context, MetricContext.FlipType flipType) {
        return context != null && context.isFlipDetected() && context.getFlipType() == flipType;
    }

    /**
     * Get overall bullish score based on all metrics
     */
    public double getBullishScore() {
        double score = 0;
        int count = 0;

        if (ofiContext != null && ofiContext.hasEnoughSamples()) {
            if (ofiContext.isBullish()) score += 1;
            else if (ofiContext.isBearish()) score -= 1;
            count++;
        }

        if (volumeDeltaContext != null && volumeDeltaContext.hasEnoughSamples()) {
            if (volumeDeltaContext.isBullish()) score += 1;
            else if (volumeDeltaContext.isBearish()) score -= 1;
            count++;
        }

        if (oiChangeContext != null && oiChangeContext.hasEnoughSamples()) {
            if (oiChangeContext.isBullish()) score += 0.5;
            else if (oiChangeContext.isBearish()) score -= 0.5;
            count++;
        }

        if (buyPressureContext != null && buyPressureContext.hasEnoughSamples()) {
            if (buyPressureContext.isBullish()) score += 0.5;
            else if (buyPressureContext.isBearish()) score -= 0.5;
            count++;
        }

        return count > 0 ? score / count : 0;
    }

    /**
     * Get the dominant regime across all metrics
     */
    public MetricContext.MetricRegime getDominantRegime() {
        int bullish = 0;
        int bearish = 0;
        int neutral = 0;

        for (MetricContext ctx : List.of(ofiContext, volumeDeltaContext, oiChangeContext, buyPressureContext)) {
            if (ctx != null && ctx.hasEnoughSamples()) {
                if (ctx.isBullish()) bullish++;
                else if (ctx.isBearish()) bearish++;
                else neutral++;
            }
        }

        if (bullish > bearish && bullish > neutral) {
            return bullish >= 3 ? MetricContext.MetricRegime.STRONG_POSITIVE : MetricContext.MetricRegime.POSITIVE;
        }
        if (bearish > bullish && bearish > neutral) {
            return bearish >= 3 ? MetricContext.MetricRegime.STRONG_NEGATIVE : MetricContext.MetricRegime.NEGATIVE;
        }
        return MetricContext.MetricRegime.NEUTRAL;
    }

    /**
     * Create a builder with defaults
     */
    public static HistoricalContextBuilder defaults() {
        return HistoricalContext.builder()
                .learningModeMetrics(new ArrayList<>())
                .dataCompleteness(0)
                .inLearningMode(true)
                .historicalConfidence(0.5);
    }
}
