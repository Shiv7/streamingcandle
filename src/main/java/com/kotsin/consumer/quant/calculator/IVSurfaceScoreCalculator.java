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
     * FIX: Added guards against division by zero when thresholds are misconfigured
     */
    private double calculateIVRankScore(IVSurface iv) {
        double maxPoints = 4.0;
        double ivRank = iv.getIvRank();
        double highThreshold = config.getIv().getRankHighThreshold();
        double lowThreshold = config.getIv().getRankLowThreshold();

        // Extreme IV ranks are actionable
        if (ivRank > highThreshold) {
            // High IV - sell vol opportunity
            // FIX: Guard against division by zero when highThreshold = 100
            double divisor = 100 - highThreshold;
            if (divisor <= 0) {
                return maxPoints * 0.7;  // Can't calculate excess, use base score
            }
            double excess = (ivRank - highThreshold) / divisor;
            return maxPoints * (0.7 + 0.3 * excess);
        } else if (ivRank < lowThreshold) {
            // Low IV - buy vol opportunity
            // FIX: Guard against division by zero when lowThreshold = 0
            if (lowThreshold <= 0) {
                return maxPoints * 0.7;  // Can't calculate deficit, use base score
            }
            double deficit = (lowThreshold - ivRank) / lowThreshold;
            return maxPoints * (0.7 + 0.3 * deficit);
        }

        // Neutral IV rank - lower score
        return maxPoints * 0.3;
    }

    /**
     * Calculate skew analysis score (0-4)
     * FIX: Added guard against division by zero when extremeSkewThreshold = 0
     */
    private double calculateSkewScore(IVSurface iv) {
        double maxPoints = 4.0;
        double skew = Math.abs(iv.getSkew25Delta());
        double extremeThreshold = config.getIv().getExtremeSkewThreshold();

        // Extreme skew is actionable
        if (skew > extremeThreshold) {
            // High skew - directional opportunity
            // FIX: Guard against division by zero when extremeSkewThreshold = 0
            if (extremeThreshold <= 0) {
                return maxPoints * 0.7;  // Can't calculate excess, use base score
            }
            double excess = (skew - extremeThreshold) / extremeThreshold;
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
