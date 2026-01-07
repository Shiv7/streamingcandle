package com.kotsin.consumer.quant.calculator;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.model.GreeksPortfolio;
import com.kotsin.consumer.model.IVSurface;
import com.kotsin.consumer.quant.config.QuantScoreConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * GreeksScoreCalculator - Calculates Greeks exposure subscore (0-15 points).
 *
 * Evaluates:
 * - Delta bias alignment with price direction (0-5)
 * - Gamma squeeze proximity (0-5)
 * - Vega structure alignment with IV regime (0-5)
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GreeksScoreCalculator {

    private final QuantScoreConfig config;

    /**
     * Calculate Greeks subscore
     *
     * @param family FamilyCandle with Greeks data
     * @param priceDirection Price direction (-1 to +1)
     * @return Score 0-15
     */
    public double calculate(FamilyCandle family, double priceDirection) {
        if (!family.hasGreeksPortfolio()) {
            return 0;
        }

        GreeksPortfolio gp = family.getGreeksPortfolio();
        double maxScore = config.getWeight().getGreeks();

        double deltaBiasScore = calculateDeltaBiasScore(gp, priceDirection);
        double gammaSqueezeScore = calculateGammaSqueezeScore(gp, family.getPrimaryPrice());
        double vegaStructureScore = calculateVegaStructureScore(gp, family);

        double totalScore = deltaBiasScore + gammaSqueezeScore + vegaStructureScore;

        return Math.min(maxScore, totalScore);
    }

    /**
     * Calculate delta bias alignment score (0-5)
     * Higher score when delta direction matches price direction
     */
    private double calculateDeltaBiasScore(GreeksPortfolio gp, double priceDirection) {
        double maxPoints = 5.0;
        double totalDelta = gp.getTotalDelta();
        double threshold = config.getGreeks().getDeltaSignificantThreshold();

        // Normalize delta
        double normalizedDelta = Math.tanh(totalDelta / threshold);

        // Check alignment: positive delta + positive price = aligned
        boolean aligned = (normalizedDelta > 0 && priceDirection > 0) ||
                         (normalizedDelta < 0 && priceDirection < 0);

        if (aligned) {
            // Score based on delta magnitude and direction strength
            double magnitude = Math.min(1.0, Math.abs(totalDelta) / threshold);
            double directionStrength = Math.abs(priceDirection);
            return maxPoints * magnitude * (0.5 + 0.5 * directionStrength);
        } else {
            // Counter-trend delta - reduce score
            double magnitude = Math.min(1.0, Math.abs(totalDelta) / threshold);
            return maxPoints * (1 - magnitude) * 0.5;
        }
    }

    /**
     * Calculate gamma squeeze proximity score (0-5)
     * Higher score when approaching max gamma strike with high concentration
     */
    private double calculateGammaSqueezeScore(GreeksPortfolio gp, double spotPrice) {
        double maxPoints = 5.0;

        if (!gp.isGammaSqueezeRisk()) {
            return 0;
        }

        Double distance = gp.getGammaSqueezeDistance();
        if (distance == null) {
            return 0;
        }

        double distanceThreshold = config.getGreeks().getGammaSqueezeDistancePercent();
        double concentrationThreshold = config.getGreeks().getGammaConcentrationThreshold();

        // Score based on proximity
        double proximityScore = 0;
        if (distance < 0.5) {
            proximityScore = maxPoints;  // Very close - maximum score
        } else if (distance < 1.0) {
            proximityScore = maxPoints * 0.8;
        } else if (distance < distanceThreshold) {
            proximityScore = maxPoints * 0.5;
        }

        // Boost for high concentration
        double concentration = gp.getGammaConcentration();
        if (concentration > concentrationThreshold) {
            proximityScore *= (1 + (concentration - concentrationThreshold) * 0.5);
        }

        return Math.min(maxPoints, proximityScore);
    }

    /**
     * Calculate vega structure alignment score (0-5)
     * Higher score when vega position aligns with IV regime
     */
    private double calculateVegaStructureScore(GreeksPortfolio gp, FamilyCandle family) {
        double maxPoints = 5.0;

        IVSurface iv = family.getIvSurface();
        if (iv == null) {
            return maxPoints * 0.5;  // Neutral if no IV data
        }

        boolean isLongVol = gp.isLongVolatility();
        boolean isIVDepressed = iv.isIVDepressed();
        boolean isIVElevated = iv.isIVElevated();

        // Long vol in low IV = good setup
        if (isLongVol && isIVDepressed) {
            return maxPoints;
        }

        // Short vol in high IV = good setup
        if (!isLongVol && isIVElevated) {
            return maxPoints;
        }

        // Long vol in high IV = bad setup
        if (isLongVol && isIVElevated) {
            return maxPoints * 0.2;
        }

        // Short vol in low IV = bad setup
        if (!isLongVol && isIVDepressed) {
            return maxPoints * 0.2;
        }

        // Neutral scenarios
        return maxPoints * 0.5;
    }
}
