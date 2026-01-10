package com.kotsin.consumer.enrichment.enricher;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.enrichment.calculator.StatisticsCalculator;
import com.kotsin.consumer.enrichment.calculator.ThresholdCalculator;
import com.kotsin.consumer.enrichment.config.CommodityConfig;
import com.kotsin.consumer.enrichment.model.HistoricalContext;
import com.kotsin.consumer.enrichment.model.MetricContext;
import com.kotsin.consumer.enrichment.state.HistoryBuffer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * HistoricalContextEnricher - Phase 1 Core Enricher
 *
 * Adds historical context to FamilyCandle by:
 * 1. Loading/updating rolling histories for all key metrics
 * 2. Calculating statistics (mean, stddev, z-score, percentile)
 * 3. Detecting regimes and regime flips
 * 4. Detecting derived signals (absorption, exhaustion, informed flow)
 *
 * This enricher transforms stateless processing into stateful intelligence.
 *
 * Integration Point:
 * - Called after FamilyCandle is created, before signal generation
 * - Outputs HistoricalContext that's attached to EnrichedFamilyCandle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoricalContextEnricher {

    private final HistoryBuffer historyBuffer;
    private final StatisticsCalculator statisticsCalculator;
    private final ThresholdCalculator thresholdCalculator;
    private final CommodityConfig commodityConfig;

    // Metric names
    private static final String METRIC_OFI = "ofi";
    private static final String METRIC_VOLUME_DELTA = "volumeDelta";
    private static final String METRIC_OI_CHANGE = "oiChange";
    private static final String METRIC_OI_VELOCITY = "oiVelocity";
    private static final String METRIC_LAMBDA = "lambda";
    private static final String METRIC_VPIN = "vpin";
    private static final String METRIC_DEPTH_IMBALANCE = "depthImbalance";
    private static final String METRIC_BUY_PRESSURE = "buyPressure";
    private static final String METRIC_SPREAD = "spread";

    // Thresholds for derived signals
    private static final double VPIN_INFORMED_THRESHOLD = 0.7;
    private static final double LAMBDA_LOW_THRESHOLD_ZSCORE = -1.0;
    private static final double LAMBDA_HIGH_THRESHOLD_ZSCORE = 2.0;
    private static final double SPREAD_HIGH_THRESHOLD_ZSCORE = 1.5;
    private static final double DEPTH_IMBALANCE_THRESHOLD = 0.3;

    /**
     * Enrich a FamilyCandle with historical context
     *
     * @param family FamilyCandle to enrich
     * @return HistoricalContext with all metric contexts and derived signals
     */
    public HistoricalContext enrich(FamilyCandle family) {
        if (family == null || family.getFamilyId() == null) {
            return HistoricalContext.defaults().build();
        }

        String familyId = family.getFamilyId();
        String timeframe = family.getTimeframe() != null ? family.getTimeframe() : "1m";
        long timestamp = family.getWindowEndMillis() > 0 ? family.getWindowEndMillis() : System.currentTimeMillis();

        try {
            // Build context for all metrics
            MetricContext ofiContext = enrichMetric(familyId, timeframe, METRIC_OFI, getOFI(family));
            MetricContext volumeDeltaContext = enrichMetric(familyId, timeframe, METRIC_VOLUME_DELTA, getVolumeDelta(family));
            MetricContext oiChangeContext = enrichMetric(familyId, timeframe, METRIC_OI_CHANGE, getOIChange(family));
            MetricContext oiVelocityContext = enrichMetric(familyId, timeframe, METRIC_OI_VELOCITY, getOIVelocity(family));
            MetricContext lambdaContext = enrichMetric(familyId, timeframe, METRIC_LAMBDA, getLambda(family));
            MetricContext vpinContext = enrichMetric(familyId, timeframe, METRIC_VPIN, getVPIN(family));
            MetricContext depthImbalanceContext = enrichMetric(familyId, timeframe, METRIC_DEPTH_IMBALANCE, getDepthImbalance(family));
            MetricContext buyPressureContext = enrichMetric(familyId, timeframe, METRIC_BUY_PRESSURE, getBuyPressure(family));
            MetricContext spreadContext = enrichMetric(familyId, timeframe, METRIC_SPREAD, getSpread(family));

            // Detect derived signals
            boolean absorptionDetected = detectAbsorption(lambdaContext, depthImbalanceContext, ofiContext);
            String absorptionType = absorptionDetected ? getAbsorptionType(ofiContext) : null;

            boolean liquidityWithdrawal = detectLiquidityWithdrawal(lambdaContext, spreadContext);
            boolean informedFlowActive = detectInformedFlow(vpinContext);
            double informedFlowIntensity = getInformedFlowIntensity(vpinContext);

            boolean sellingExhaustion = detectSellingExhaustion(ofiContext, oiVelocityContext);
            boolean buyingExhaustion = detectBuyingExhaustion(ofiContext, oiVelocityContext);

            boolean momentumBuilding = detectMomentumBuilding(ofiContext, volumeDeltaContext);
            String momentumDirection = momentumBuilding ? getMomentumDirection(ofiContext) : null;

            // Calculate flip summary
            int totalFlips = countFlips(ofiContext, volumeDeltaContext, oiChangeContext, buyPressureContext);
            MetricContext significantFlip = findMostSignificantFlip(ofiContext, volumeDeltaContext, oiChangeContext);

            // Calculate learning mode status
            List<String> learningMetrics = getLearningModeMetrics(
                    ofiContext, volumeDeltaContext, oiChangeContext, lambdaContext, vpinContext);
            double dataCompleteness = calculateDataCompleteness(
                    ofiContext, volumeDeltaContext, oiChangeContext, lambdaContext, vpinContext);
            boolean inLearningMode = dataCompleteness < 0.8;

            // Calculate overall confidence
            double historicalConfidence = calculateHistoricalConfidence(
                    dataCompleteness, totalFlips, absorptionDetected, informedFlowActive);

            // Build and return context
            return HistoricalContext.builder()
                    .familyId(familyId)
                    .timeframe(timeframe)
                    .timestamp(Instant.ofEpochMilli(timestamp))
                    .timestampMillis(timestamp)
                    // Metric contexts
                    .ofiContext(ofiContext)
                    .volumeDeltaContext(volumeDeltaContext)
                    .oiChangeContext(oiChangeContext)
                    .oiVelocityContext(oiVelocityContext)
                    .lambdaContext(lambdaContext)
                    .vpinContext(vpinContext)
                    .depthImbalanceContext(depthImbalanceContext)
                    .buyPressureContext(buyPressureContext)
                    .spreadContext(spreadContext)
                    // Derived signals
                    .absorptionDetected(absorptionDetected)
                    .absorptionType(absorptionType)
                    .liquidityWithdrawal(liquidityWithdrawal)
                    .informedFlowActive(informedFlowActive)
                    .informedFlowIntensity(informedFlowIntensity)
                    .sellingExhaustion(sellingExhaustion)
                    .buyingExhaustion(buyingExhaustion)
                    .momentumBuilding(momentumBuilding)
                    .momentumDirection(momentumDirection)
                    // Flip summary
                    .totalFlipsDetected(totalFlips)
                    .significantFlipMetric(significantFlip != null ? significantFlip.getMetricName() : null)
                    .significantFlipType(significantFlip != null ? significantFlip.getFlipType() : null)
                    .significantFlipZscore(significantFlip != null ? significantFlip.getFlipZscore() : 0)
                    // Learning mode
                    .dataCompleteness(dataCompleteness)
                    .inLearningMode(inLearningMode)
                    .learningModeMetrics(learningMetrics)
                    .historicalConfidence(historicalConfidence)
                    .build();

        } catch (Exception e) {
            log.error("Error enriching historical context for {}:{}: {}", familyId, timeframe, e.getMessage());
            return HistoricalContext.defaults()
                    .familyId(familyId)
                    .timeframe(timeframe)
                    .build();
        }
    }

    // ======================== METRIC EXTRACTION ========================

    private MetricContext enrichMetric(String familyId, String timeframe, String metricName, double value) {
        return historyBuffer.addAndGetContext(familyId, timeframe, metricName, value);
    }

    private double getOFI(FamilyCandle family) {
        // Get OFI from primary instrument
        if (family.getEquity() != null && family.getEquity().getOfi() != null) {
            return family.getEquity().getOfi();
        }
        if (family.getFuture() != null && family.getFuture().getOfi() != null) {
            return family.getFuture().getOfi();
        }
        return 0.0;
    }

    private double getVolumeDelta(FamilyCandle family) {
        // Volume delta = buy volume - sell volume
        // Note: buyVolume and sellVolume are primitives (long), so can't be null
        if (family.getEquity() != null) {
            long buyVol = family.getEquity().getBuyVolume();
            long sellVol = family.getEquity().getSellVolume();
            return buyVol - sellVol;
        }
        if (family.getFuture() != null) {
            long buyVol = family.getFuture().getBuyVolume();
            long sellVol = family.getFuture().getSellVolume();
            return buyVol - sellVol;
        }
        return 0.0;
    }

    private double getOIChange(FamilyCandle family) {
        if (family.getFuture() != null && family.getFuture().getOiChange() != null) {
            return family.getFuture().getOiChange();
        }
        return 0.0;
    }

    private double getOIVelocity(FamilyCandle family) {
        if (family.getFuture() != null && family.getFuture().getOiVelocity() != null) {
            return family.getFuture().getOiVelocity();
        }
        return 0.0;
    }

    private double getLambda(FamilyCandle family) {
        // Kyle's Lambda from primary instrument
        if (family.getEquity() != null && family.getEquity().getKyleLambda() != null) {
            return family.getEquity().getKyleLambda();
        }
        if (family.getFuture() != null && family.getFuture().getKyleLambda() != null) {
            return family.getFuture().getKyleLambda();
        }
        return 0.0;
    }

    private double getVPIN(FamilyCandle family) {
        // vpin is a primitive double, default to 0.5 if zero (uninitialized)
        if (family.getEquity() != null && family.getEquity().getVpin() > 0) {
            return family.getEquity().getVpin();
        }
        if (family.getFuture() != null && family.getFuture().getVpin() > 0) {
            return family.getFuture().getVpin();
        }
        return 0.5; // Default neutral VPIN
    }

    private double getDepthImbalance(FamilyCandle family) {
        if (family.getEquity() != null && family.getEquity().getDepthImbalance() != null) {
            return family.getEquity().getDepthImbalance();
        }
        if (family.getFuture() != null && family.getFuture().getDepthImbalance() != null) {
            return family.getFuture().getDepthImbalance();
        }
        return 0.0;
    }

    private double getBuyPressure(FamilyCandle family) {
        if (family.getEquity() != null && family.getEquity().getBuyPressure() != null) {
            return family.getEquity().getBuyPressure();
        }
        if (family.getFuture() != null && family.getFuture().getBuyPressure() != null) {
            return family.getFuture().getBuyPressure();
        }
        return 0.5;
    }

    private double getSpread(FamilyCandle family) {
        if (family.getEquity() != null && family.getEquity().getBidAskSpread() != null) {
            return family.getEquity().getBidAskSpread();
        }
        if (family.getFuture() != null && family.getFuture().getBidAskSpread() != null) {
            return family.getFuture().getBidAskSpread();
        }
        return 0.0;
    }

    // ======================== DERIVED SIGNAL DETECTION ========================

    /**
     * Absorption Detection: Large orders absorbed without price impact
     * Conditions:
     * - Lambda z-score < -1.0 (unusually low price impact)
     * - Depth imbalance showing resistance
     * - OFI showing aggression against the imbalance
     */
    private boolean detectAbsorption(MetricContext lambdaCtx, MetricContext depthCtx, MetricContext ofiCtx) {
        if (lambdaCtx == null || !lambdaCtx.hasEnoughSamples()) return false;
        if (depthCtx == null || !depthCtx.hasEnoughSamples()) return false;
        if (ofiCtx == null || !ofiCtx.hasEnoughSamples()) return false;

        boolean lowLambda = lambdaCtx.getZscore() < LAMBDA_LOW_THRESHOLD_ZSCORE;
        boolean depthResistance = Math.abs(depthCtx.getCurrentValue()) > DEPTH_IMBALANCE_THRESHOLD;
        boolean aggressiveFlow = Math.abs(ofiCtx.getZscore()) > 1.0;

        // Bullish absorption: Ask heavy (negative depth) but buyers aggressive (positive OFI)
        // Bearish absorption: Bid heavy (positive depth) but sellers aggressive (negative OFI)
        boolean opposingForces = (depthCtx.getCurrentValue() < 0 && ofiCtx.getCurrentValue() > 0)
                || (depthCtx.getCurrentValue() > 0 && ofiCtx.getCurrentValue() < 0);

        return lowLambda && depthResistance && aggressiveFlow && opposingForces;
    }

    private String getAbsorptionType(MetricContext ofiCtx) {
        if (ofiCtx == null) return null;
        return ofiCtx.getCurrentValue() > 0 ? "BULLISH_ACCUMULATION" : "BEARISH_DISTRIBUTION";
    }

    /**
     * Liquidity Withdrawal: Market makers stepping away
     * Conditions:
     * - Lambda z-score > 2.0 (high price impact)
     * - Spread z-score > 1.5 (widening spread)
     */
    private boolean detectLiquidityWithdrawal(MetricContext lambdaCtx, MetricContext spreadCtx) {
        if (lambdaCtx == null || !lambdaCtx.hasEnoughSamples()) return false;
        if (spreadCtx == null || !spreadCtx.hasEnoughSamples()) return false;

        return lambdaCtx.getZscore() > LAMBDA_HIGH_THRESHOLD_ZSCORE
                && spreadCtx.getZscore() > SPREAD_HIGH_THRESHOLD_ZSCORE;
    }

    /**
     * Informed Flow: Smart money active
     * Conditions: VPIN > 0.7
     */
    private boolean detectInformedFlow(MetricContext vpinCtx) {
        if (vpinCtx == null) return false;
        return vpinCtx.getCurrentValue() > VPIN_INFORMED_THRESHOLD;
    }

    private double getInformedFlowIntensity(MetricContext vpinCtx) {
        if (vpinCtx == null) return 0;
        return Math.max(0, (vpinCtx.getCurrentValue() - 0.5) * 2); // Scale 0.5-1.0 to 0-1
    }

    /**
     * Selling Exhaustion: Sellers losing steam
     * Conditions: OFI negative but velocity turning positive
     */
    private boolean detectSellingExhaustion(MetricContext ofiCtx, MetricContext velocityCtx) {
        if (ofiCtx == null || velocityCtx == null) return false;
        if (!ofiCtx.hasEnoughSamples()) return false;

        return ofiCtx.getCurrentValue() < 0 && velocityCtx.getCurrentValue() > 0;
    }

    /**
     * Buying Exhaustion: Buyers losing steam
     * Conditions: OFI positive but velocity turning negative
     */
    private boolean detectBuyingExhaustion(MetricContext ofiCtx, MetricContext velocityCtx) {
        if (ofiCtx == null || velocityCtx == null) return false;
        if (!ofiCtx.hasEnoughSamples()) return false;

        return ofiCtx.getCurrentValue() > 0 && velocityCtx.getCurrentValue() < 0;
    }

    /**
     * Momentum Building: Accelerating in same direction
     */
    private boolean detectMomentumBuilding(MetricContext ofiCtx, MetricContext volumeDeltaCtx) {
        if (ofiCtx == null || volumeDeltaCtx == null) return false;
        if (!ofiCtx.hasEnoughSamples()) return false;

        // Same direction for OFI and Volume Delta, both strong
        boolean sameDirection = (ofiCtx.isBullish() && volumeDeltaCtx.isBullish())
                || (ofiCtx.isBearish() && volumeDeltaCtx.isBearish());

        boolean strong = ofiCtx.getConsecutiveCount() >= 3;

        return sameDirection && strong;
    }

    private String getMomentumDirection(MetricContext ofiCtx) {
        if (ofiCtx == null) return null;
        return ofiCtx.isBullish() ? "BULLISH" : "BEARISH";
    }

    // ======================== HELPER METHODS ========================

    private int countFlips(MetricContext... contexts) {
        int count = 0;
        for (MetricContext ctx : contexts) {
            if (ctx != null && ctx.isFlipDetected()) {
                count++;
            }
        }
        return count;
    }

    private MetricContext findMostSignificantFlip(MetricContext... contexts) {
        MetricContext mostSignificant = null;
        double highestZscore = 0;

        for (MetricContext ctx : contexts) {
            if (ctx != null && ctx.isFlipDetected()) {
                if (ctx.getFlipZscore() > highestZscore) {
                    highestZscore = ctx.getFlipZscore();
                    mostSignificant = ctx;
                }
            }
        }
        return mostSignificant;
    }

    private List<String> getLearningModeMetrics(MetricContext... contexts) {
        List<String> learning = new ArrayList<>();
        String[] names = {METRIC_OFI, METRIC_VOLUME_DELTA, METRIC_OI_CHANGE, METRIC_LAMBDA, METRIC_VPIN};

        for (int i = 0; i < contexts.length && i < names.length; i++) {
            if (contexts[i] == null || contexts[i].isInLearningMode()) {
                learning.add(names[i]);
            }
        }
        return learning;
    }

    private double calculateDataCompleteness(MetricContext... contexts) {
        if (contexts.length == 0) return 0;

        int complete = 0;
        for (MetricContext ctx : contexts) {
            if (ctx != null && ctx.hasEnoughSamples()) {
                complete++;
            }
        }
        return (double) complete / contexts.length;
    }

    private double calculateHistoricalConfidence(double dataCompleteness,
                                                   int totalFlips,
                                                   boolean absorptionDetected,
                                                   boolean informedFlowActive) {
        double confidence = 0.5 + (dataCompleteness * 0.3); // Base from data completeness

        // Boost for actionable signals
        if (totalFlips > 0) confidence += 0.1;
        if (absorptionDetected) confidence += 0.05;
        if (informedFlowActive) confidence += 0.05;

        return Math.min(confidence, 1.0);
    }
}
