package com.kotsin.consumer.quant.calculator;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.model.IVSurface;
import com.kotsin.consumer.quant.config.QuantScoreConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * IVSurfaceScoreCalculator - Calculates IV surface subscore (0-12 points).
 *
 * Evaluates:
 * - IV rank signal strength (0-4)
 * - Skew analysis (0-4)
 * - Term structure (0-4)
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class IVSurfaceScoreCalculator {

    private final QuantScoreConfig config;

    /**
     * Calculate IV surface subscore
     *
     * @param family FamilyCandle with IV data
     * @return Score 0-12
     */
    public double calculate(FamilyCandle family) {
        double maxScore = config.getWeight().getIvSurface();

        IVSurface iv = family.getIvSurface();
        if (iv == null || !iv.hasData()) {
            // Fall back to basic ATM IV if available
            Double atmIV = family.getAtmIV();
            if (atmIV != null && atmIV > 0) {
                return maxScore * 0.3;  // Partial score for basic data
            }
            return 0;
        }

        double ivRankScore = calculateIVRankScore(iv);
        double skewScore = calculateSkewScore(iv);
        double termScore = calculateTermStructureScore(iv);

        return Math.min(maxScore, ivRankScore + skewScore + termScore);
    }

    /**
     * Calculate IV rank signal score (0-4)
     */
    private double calculateIVRankScore(IVSurface iv) {
        double maxPoints = 4.0;
        double ivRank = iv.getIvRank();

        // Extreme IV ranks are actionable
        if (ivRank > config.getIv().getRankHighThreshold()) {
            // High IV - sell vol opportunity
            double excess = (ivRank - config.getIv().getRankHighThreshold()) /
                           (100 - config.getIv().getRankHighThreshold());
            return maxPoints * (0.7 + 0.3 * excess);
        } else if (ivRank < config.getIv().getRankLowThreshold()) {
            // Low IV - buy vol opportunity
            double deficit = (config.getIv().getRankLowThreshold() - ivRank) /
                            config.getIv().getRankLowThreshold();
            return maxPoints * (0.7 + 0.3 * deficit);
        }

        // Neutral IV rank - lower score
        return maxPoints * 0.3;
    }

    /**
     * Calculate skew analysis score (0-4)
     */
    private double calculateSkewScore(IVSurface iv) {
        double maxPoints = 4.0;
        double skew = Math.abs(iv.getSkew25Delta());

        // Extreme skew is actionable
        if (skew > config.getIv().getExtremeSkewThreshold()) {
            // High skew - directional opportunity
            double excess = (skew - config.getIv().getExtremeSkewThreshold()) /
                           config.getIv().getExtremeSkewThreshold();
            return maxPoints * Math.min(1.0, 0.7 + 0.3 * excess);
        }

        // Moderate skew
        if (skew > 2.0) {
            return maxPoints * 0.5;
        }

        // Low skew - neutral markets
        return maxPoints * 0.3;
    }

    /**
     * Calculate term structure score (0-4)
     */
    private double calculateTermStructureScore(IVSurface iv) {
        double maxPoints = 4.0;

        IVSurface.TermStructure term = iv.getTermStructure();
        if (term == null) {
            return maxPoints * 0.3;
        }

        switch (term) {
            case BACKWARDATION:
                // Event/stress - high opportunity
                return maxPoints;

            case CONTANGO:
                // Normal market - check if extreme
                double termSlope = Math.abs(iv.getTermSlope());
                if (termSlope > 3.0) {
                    return maxPoints * 0.8;  // Steep contango
                }
                return maxPoints * 0.5;

            case FLAT:
            default:
                return maxPoints * 0.3;
        }
    }
}
