package com.kotsin.consumer.quant.signal;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.model.GreeksPortfolio;
import com.kotsin.consumer.model.IVSurface;
import com.kotsin.consumer.quant.config.QuantScoreConfig;
import com.kotsin.consumer.quant.model.QuantScore;
import com.kotsin.consumer.quant.model.QuantTradingSignal.HedgingRecommendation;
import com.kotsin.consumer.quant.model.QuantTradingSignal.HedgingRecommendation.HedgeType;
import com.kotsin.consumer.quant.model.QuantTradingSignal.HedgingRecommendation.HedgeUrgency;
import com.kotsin.consumer.quant.model.QuantTradingSignal.SignalDirection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * HedgingRecommendationEngine - Generates Greeks-based hedging suggestions.
 *
 * Analyzes:
 * - Delta exposure for directional hedging
 * - Gamma exposure for convexity management
 * - Vega exposure for volatility hedging
 * - Tail risk for protective hedges
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HedgingRecommendationEngine {

    private final QuantScoreConfig config;

    // Thresholds for hedging recommendations
    private static final double DELTA_HEDGE_THRESHOLD = 3000;
    private static final double GAMMA_HEDGE_THRESHOLD = 2000;
    private static final double VEGA_HEDGE_THRESHOLD = 30000;
    private static final double GAMMA_SQUEEZE_DISTANCE_URGENT = 1.0;

    /**
     * Generate hedging recommendation based on Greeks exposure
     *
     * @param score QuantScore with Greeks summary
     * @param family FamilyCandle with options data
     * @param direction Signal direction
     * @return HedgingRecommendation or null if no hedge needed
     */
    public HedgingRecommendation recommend(QuantScore score, FamilyCandle family,
                                            SignalDirection direction) {
        GreeksPortfolio gp = family.getGreeksPortfolio();
        IVSurface iv = family.getIvSurface();

        if (gp == null || !gp.hasExposure()) {
            return noHedgeNeeded("No Greeks exposure data");
        }

        // Check for urgent gamma squeeze hedge
        if (gp.isGammaSqueezeRisk()) {
            Double distance = gp.getGammaSqueezeDistance();
            if (distance != null && distance < GAMMA_SQUEEZE_DISTANCE_URGENT) {
                return buildGammaSqueezeHedge(gp, family, direction);
            }
        }

        // Check for significant delta exposure
        if (Math.abs(gp.getTotalDelta()) > DELTA_HEDGE_THRESHOLD) {
            return buildDeltaHedge(gp, family, direction);
        }

        // Check for significant gamma exposure
        if (Math.abs(gp.getTotalGamma()) > GAMMA_HEDGE_THRESHOLD) {
            return buildGammaHedge(gp, family, direction);
        }

        // Check for significant vega exposure
        if (Math.abs(gp.getTotalVega()) > VEGA_HEDGE_THRESHOLD) {
            return buildVegaHedge(gp, iv, family, direction);
        }

        // Check for tail risk in extreme IV
        if (iv != null && (iv.getIvRank() > 85 || iv.getIvRank() < 15)) {
            return buildTailHedge(gp, iv, family, direction);
        }

        return noHedgeNeeded("Exposure within acceptable limits");
    }

    /**
     * Build gamma squeeze hedge - URGENT
     */
    private HedgingRecommendation buildGammaSqueezeHedge(GreeksPortfolio gp, FamilyCandle family,
                                                          SignalDirection direction) {
        double spotPrice = family.getPrimaryPrice();
        double maxGammaStrike = gp.getMaxGammaStrike();
        double totalGamma = gp.getTotalGamma();

        // Determine hedge direction based on gamma sign and position
        boolean needsProtectivePut = direction == SignalDirection.LONG;
        String hedgeInstrument = needsProtectivePut ?
            String.format("%.0f PE", maxGammaStrike) :
            String.format("%.0f CE", maxGammaStrike);

        // Calculate hedge ratio - higher for imminent squeeze
        Double distance = gp.getGammaSqueezeDistance();
        double hedgeRatio = distance != null ?
            Math.min(1.0, 2.0 / distance) : 0.5;

        return HedgingRecommendation.builder()
            .hedgeNeeded(true)
            .hedgeType(HedgeType.GAMMA_HEDGE)
            .urgency(HedgeUrgency.IMMEDIATE)
            .hedgeInstrument(hedgeInstrument)
            .hedgeStrike(String.valueOf(maxGammaStrike))
            .hedgeRatio(hedgeRatio)
            .gammaExposure(totalGamma)
            .gammaStrategy("BUY_STRADDLE")
            .hedgeRationale(String.format(
                "Gamma squeeze imminent at %.0f (%.2f%% away). High gamma concentration. " +
                "Hedge with straddle or protective option.",
                maxGammaStrike, distance != null ? distance : 0))
            .estimatedHedgeCost(spotPrice * 0.02 * hedgeRatio)  // ~2% of spot
            .hedgeCostPercent(2.0 * hedgeRatio)
            .build();
    }

    /**
     * Build delta hedge recommendation
     */
    private HedgingRecommendation buildDeltaHedge(GreeksPortfolio gp, FamilyCandle family,
                                                   SignalDirection direction) {
        double totalDelta = gp.getTotalDelta();
        double spotPrice = family.getPrimaryPrice();

        // Delta to neutralize depends on position
        double deltaToNeutralize = direction == SignalDirection.LONG ?
            Math.min(0, -totalDelta * 0.5) :  // Neutralize half of long delta
            Math.max(0, -totalDelta * 0.5);   // Neutralize half of short delta

        String hedgeAction = totalDelta > 0 ?
            "BUY_PUTS" : "BUY_CALLS";

        // Find ATM strike
        double atmStrike = Math.round(spotPrice / 100) * 100;
        String hedgeInstrument = totalDelta > 0 ?
            String.format("%.0f PE", atmStrike) :
            String.format("%.0f CE", atmStrike);

        // Hedge ratio based on delta magnitude
        double hedgeRatio = Math.min(1.0, Math.abs(totalDelta) / (DELTA_HEDGE_THRESHOLD * 2));

        return HedgingRecommendation.builder()
            .hedgeNeeded(true)
            .hedgeType(HedgeType.DELTA_HEDGE)
            .urgency(HedgeUrgency.ON_ENTRY)
            .hedgeInstrument(hedgeInstrument)
            .hedgeStrike(String.valueOf(atmStrike))
            .hedgeRatio(hedgeRatio)
            .deltaToNeutralize(deltaToNeutralize)
            .suggestedHedgeDelta(-deltaToNeutralize)
            .deltaHedgeAction(hedgeAction)
            .hedgeRationale(String.format(
                "Significant delta exposure: %.0f. %s to reduce directional risk by %.0f%%.",
                totalDelta, hedgeAction, hedgeRatio * 100))
            .estimatedHedgeCost(spotPrice * 0.015 * hedgeRatio)
            .hedgeCostPercent(1.5 * hedgeRatio)
            .build();
    }

    /**
     * Build gamma hedge recommendation
     */
    private HedgingRecommendation buildGammaHedge(GreeksPortfolio gp, FamilyCandle family,
                                                   SignalDirection direction) {
        double totalGamma = gp.getTotalGamma();
        double spotPrice = family.getPrimaryPrice();
        double maxGammaStrike = gp.getMaxGammaStrike();

        // Gamma hedge strategies
        String gammaStrategy = totalGamma > 0 ?
            "SELL_STRANGLE" :  // Long gamma -> can sell wings
            "BUY_STRADDLE";    // Short gamma -> need to buy convexity

        double hedgeRatio = Math.min(1.0, Math.abs(totalGamma) / (GAMMA_HEDGE_THRESHOLD * 2));

        return HedgingRecommendation.builder()
            .hedgeNeeded(true)
            .hedgeType(HedgeType.GAMMA_HEDGE)
            .urgency(HedgeUrgency.AFTER_ENTRY)
            .hedgeStrike(String.valueOf(maxGammaStrike))
            .hedgeRatio(hedgeRatio)
            .gammaExposure(totalGamma)
            .gammaStrategy(gammaStrategy)
            .hedgeRationale(String.format(
                "High gamma exposure: %.0f at strike %.0f. Consider %s to manage convexity.",
                totalGamma, maxGammaStrike, gammaStrategy))
            .estimatedHedgeCost(spotPrice * 0.01 * hedgeRatio)
            .hedgeCostPercent(1.0 * hedgeRatio)
            .build();
    }

    /**
     * Build vega hedge recommendation
     */
    private HedgingRecommendation buildVegaHedge(GreeksPortfolio gp, IVSurface iv,
                                                  FamilyCandle family, SignalDirection direction) {
        double totalVega = gp.getTotalVega();
        double spotPrice = family.getPrimaryPrice();

        // Vega hedge strategies based on IV regime
        String vegaStrategy;
        if (iv != null && iv.getIvRank() > 70) {
            vegaStrategy = totalVega > 0 ?
                "CALENDAR_SPREAD" :    // Long vega in high IV -> calendar
                "SELL_STRADDLE";       // Short vega in high IV -> already good
        } else {
            vegaStrategy = totalVega > 0 ?
                "HOLD" :               // Long vega in low IV -> hold
                "DIAGONAL_SPREAD";     // Short vega in low IV -> diagonal
        }

        double hedgeRatio = Math.min(1.0, Math.abs(totalVega) / (VEGA_HEDGE_THRESHOLD * 2));

        return HedgingRecommendation.builder()
            .hedgeNeeded(!"HOLD".equals(vegaStrategy))
            .hedgeType(HedgeType.VEGA_HEDGE)
            .urgency(HedgeUrgency.OPTIONAL)
            .hedgeRatio(hedgeRatio)
            .vegaExposure(totalVega)
            .vegaStrategy(vegaStrategy)
            .hedgeRationale(String.format(
                "Vega exposure: %.0f. IV Rank: %.0f. Strategy: %s.",
                totalVega, iv != null ? iv.getIvRank() : 50, vegaStrategy))
            .estimatedHedgeCost(spotPrice * 0.005 * hedgeRatio)
            .hedgeCostPercent(0.5 * hedgeRatio)
            .build();
    }

    /**
     * Build tail hedge for extreme IV
     */
    private HedgingRecommendation buildTailHedge(GreeksPortfolio gp, IVSurface iv,
                                                  FamilyCandle family, SignalDirection direction) {
        double spotPrice = family.getPrimaryPrice();

        // OTM protective options for tail risk
        double otmStrike = direction == SignalDirection.LONG ?
            spotPrice * 0.95 :  // 5% OTM put for long
            spotPrice * 1.05;   // 5% OTM call for short

        String hedgeInstrument = direction == SignalDirection.LONG ?
            String.format("%.0f PE", otmStrike) :
            String.format("%.0f CE", otmStrike);

        return HedgingRecommendation.builder()
            .hedgeNeeded(true)
            .hedgeType(HedgeType.TAIL_HEDGE)
            .urgency(HedgeUrgency.OPTIONAL)
            .hedgeInstrument(hedgeInstrument)
            .hedgeStrike(String.valueOf(otmStrike))
            .hedgeRatio(0.25)  // 25% position hedge
            .hedgeRationale(String.format(
                "Extreme IV regime (Rank: %.0f). Protective %s for tail risk.",
                iv != null ? iv.getIvRank() : 50,
                direction == SignalDirection.LONG ? "put" : "call"))
            .estimatedHedgeCost(spotPrice * 0.005)
            .hedgeCostPercent(0.5)
            .build();
    }

    /**
     * Return no hedge needed
     */
    private HedgingRecommendation noHedgeNeeded(String reason) {
        return HedgingRecommendation.builder()
            .hedgeNeeded(false)
            .hedgeType(HedgeType.NO_HEDGE)
            .urgency(HedgeUrgency.OPTIONAL)
            .hedgeRationale(reason)
            .hedgeRatio(0)
            .estimatedHedgeCost(0)
            .hedgeCostPercent(0)
            .build();
    }
}
