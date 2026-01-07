package com.kotsin.consumer.quant.calculator;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.model.MTFDistribution;
import com.kotsin.consumer.model.EvolutionMetrics;
import com.kotsin.consumer.quant.config.QuantScoreConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * OptionsFlowScoreCalculator - Calculates options flow subscore (0-15 points).
 *
 * Evaluates:
 * - PCR signal (0-5)
 * - OI evolution pattern (0-5)
 * - Futures buildup alignment (0-5)
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OptionsFlowScoreCalculator {

    private final QuantScoreConfig config;

    /**
     * Calculate options flow subscore
     *
     * @param family FamilyCandle with PCR, OI data
     * @param priceDirection Price direction for alignment check
     * @return Score 0-15
     */
    public double calculate(FamilyCandle family, double priceDirection) {
        double maxScore = config.getWeight().getOptionsFlow();

        double pcrScore = calculatePCRScore(family);
        double oiScore = calculateOIEvolutionScore(family, priceDirection);
        double futuresScore = calculateFuturesBuildupScore(family, priceDirection);

        return Math.min(maxScore, pcrScore + oiScore + futuresScore);
    }

    /**
     * Calculate PCR signal score (0-5)
     */
    private double calculatePCRScore(FamilyCandle family) {
        double maxPoints = 5.0;
        Double pcr = family.getPcr();

        if (pcr == null) {
            return 0;
        }

        // Extreme PCR values are contrarian signals
        if (pcr > config.getOptionsFlow().getPcrExtremeFear()) {
            // Extreme fear (high puts) - contrarian bullish
            double excess = (pcr - config.getOptionsFlow().getPcrExtremeFear()) /
                           config.getOptionsFlow().getPcrExtremeFear();
            return maxPoints * Math.min(1.0, 0.7 + 0.3 * excess);
        } else if (pcr < config.getOptionsFlow().getPcrExtremeGreed()) {
            // Extreme greed (low puts) - contrarian bearish
            double deficit = (config.getOptionsFlow().getPcrExtremeGreed() - pcr) /
                            config.getOptionsFlow().getPcrExtremeGreed();
            return maxPoints * Math.min(1.0, 0.7 + 0.3 * deficit);
        } else if (pcr < config.getOptionsFlow().getPcrBullishThreshold()) {
            // Normal bullish range
            return maxPoints * 0.6;
        } else if (pcr > config.getOptionsFlow().getPcrBearishThreshold()) {
            // Normal bearish range
            return maxPoints * 0.6;
        }

        // Neutral PCR
        return maxPoints * 0.3;
    }

    /**
     * Calculate OI evolution pattern score (0-5)
     */
    private double calculateOIEvolutionScore(FamilyCandle family, double priceDirection) {
        double maxPoints = 5.0;

        MTFDistribution mtf = family.getMtfDistribution();
        if (mtf == null || mtf.getEvolution() == null ||
            mtf.getEvolution().getOiEvolution() == null) {
            // Fall back to basic OI signals
            return calculateBasicOIScore(family, priceDirection);
        }

        EvolutionMetrics.OIEvolution oiEvo = mtf.getEvolution().getOiEvolution();
        String buildupType = oiEvo.getBuildupType() != null ? oiEvo.getBuildupType().name() : null;

        if (buildupType == null) {
            return maxPoints * 0.3;
        }

        // Check alignment with price direction
        boolean aligned = false;
        double confidence = oiEvo.getBuildupConfidence();

        switch (buildupType) {
            case "LONG_BUILDUP":
                aligned = priceDirection > 0;  // Price going up
                break;
            case "SHORT_BUILDUP":
                aligned = priceDirection < 0;  // Price going down
                break;
            case "LONG_UNWINDING":
                aligned = priceDirection < 0;  // Price falling
                break;
            case "SHORT_COVERING":
                aligned = priceDirection > 0;  // Price rising
                break;
        }

        if (aligned) {
            return maxPoints * (0.7 + 0.3 * confidence);
        } else {
            // Counter-trend OI - potential reversal signal
            return maxPoints * 0.4;
        }
    }

    /**
     * Fallback basic OI score calculation
     */
    private double calculateBasicOIScore(FamilyCandle family, double priceDirection) {
        double maxPoints = 5.0;

        boolean callBuilding = family.isCallOiBuildingUp();
        boolean putUnwinding = family.isPutOiUnwinding();
        boolean bullishOI = family.isBullishOI();
        boolean bearishOI = family.isBearishOI();

        // Aligned with direction
        if ((bullishOI && priceDirection > 0) || (bearishOI && priceDirection < 0)) {
            return maxPoints * 0.7;
        }

        // Call building + put unwinding = bullish
        if (callBuilding && putUnwinding && priceDirection > 0) {
            return maxPoints * 0.8;
        }

        return maxPoints * 0.3;
    }

    /**
     * Calculate futures buildup alignment score (0-5)
     */
    private double calculateFuturesBuildupScore(FamilyCandle family, double priceDirection) {
        double maxPoints = 5.0;
        String futuresBuildup = family.getFuturesBuildup();

        if (futuresBuildup == null) {
            return 0;
        }

        boolean aligned = false;

        switch (futuresBuildup) {
            case "LONG_BUILDUP":
                aligned = priceDirection > 0;
                break;
            case "SHORT_BUILDUP":
                aligned = priceDirection < 0;
                break;
            case "LONG_UNWINDING":
                aligned = priceDirection < 0;
                break;
            case "SHORT_COVERING":
                aligned = priceDirection > 0;
                break;
        }

        // Also check spot-future premium
        Double premium = family.getSpotFuturePremium();
        boolean premiumAligned = false;

        if (premium != null) {
            if (premium > 0.5 && priceDirection > 0) {
                premiumAligned = true;  // Positive premium + bullish
            } else if (premium < -0.5 && priceDirection < 0) {
                premiumAligned = true;  // Negative premium + bearish
            }
        }

        if (aligned && premiumAligned) {
            return maxPoints;  // Double confirmation
        } else if (aligned || premiumAligned) {
            return maxPoints * 0.7;
        }

        return maxPoints * 0.3;
    }
}
