package com.kotsin.consumer.quant.calculator;

import com.kotsin.consumer.quant.config.QuantScoreConfig;
import com.kotsin.consumer.quant.model.QuantScore;
import com.kotsin.consumer.quant.model.QuantScore.Direction;
import com.kotsin.consumer.quant.model.QuantScore.QuantScoreBreakdown;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ConfluenceScoreCalculator - Calculates confluence subscore (0-10 points).
 *
 * Evaluates agreement across all other categories.
 * High confluence = multiple categories confirming same direction.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ConfluenceScoreCalculator {

    private final QuantScoreConfig config;

    private static final double CATEGORY_THRESHOLD = 60.0;  // % of max to consider "strong"

    /**
     * Calculate confluence subscore based on category agreement
     *
     * @param breakdown Breakdown with individual category scores
     * @param direction Overall direction being analyzed
     * @return Score 0-10
     */
    public double calculate(QuantScoreBreakdown breakdown, Direction direction) {
        double maxScore = config.getWeight().getConfluence();

        if (breakdown == null) {
            return 0;
        }

        // Count categories above threshold
        int strongCategories = countStrongCategories(breakdown);

        // Calculate agreement score based on how many categories agree
        // 8 categories total
        double agreementRatio = strongCategories / 8.0;

        // Base score from agreement
        double baseScore = maxScore * agreementRatio;

        // Bonus for high concentration of strong signals
        if (strongCategories >= 6) {
            baseScore *= 1.2;  // 20% bonus for 6+ categories
        } else if (strongCategories >= 4) {
            baseScore *= 1.1;  // 10% bonus for 4-5 categories
        }

        // Penalty for very low confluence
        if (strongCategories <= 1) {
            baseScore *= 0.5;
        }

        // Check for consistency in scores (low variance = higher confluence)
        double variance = calculateScoreVariance(breakdown);
        if (variance < 200) {
            baseScore *= 1.1;  // Low variance bonus
        } else if (variance > 500) {
            baseScore *= 0.9;  // High variance penalty
        }

        return Math.min(maxScore, baseScore);
    }

    /**
     * Count categories above threshold percentage
     */
    private int countStrongCategories(QuantScoreBreakdown breakdown) {
        int count = 0;
        double threshold = CATEGORY_THRESHOLD;

        if (breakdown.getGreeksPct() >= threshold) count++;
        if (breakdown.getIvSurfacePct() >= threshold) count++;
        if (breakdown.getMicrostructurePct() >= threshold) count++;
        if (breakdown.getOptionsFlowPct() >= threshold) count++;
        if (breakdown.getPriceActionPct() >= threshold) count++;
        if (breakdown.getVolumeProfilePct() >= threshold) count++;
        if (breakdown.getCrossInstrumentPct() >= threshold) count++;
        // Confluence is calculated last, so we don't include it here

        return count;
    }

    /**
     * Calculate variance of category percentages
     */
    private double calculateScoreVariance(QuantScoreBreakdown breakdown) {
        double[] scores = {
            breakdown.getGreeksPct(),
            breakdown.getIvSurfacePct(),
            breakdown.getMicrostructurePct(),
            breakdown.getOptionsFlowPct(),
            breakdown.getPriceActionPct(),
            breakdown.getVolumeProfilePct(),
            breakdown.getCrossInstrumentPct()
        };

        // Calculate mean
        double sum = 0;
        for (double s : scores) {
            sum += s;
        }
        double mean = sum / scores.length;

        // Calculate variance
        double varianceSum = 0;
        for (double s : scores) {
            varianceSum += (s - mean) * (s - mean);
        }

        return varianceSum / scores.length;
    }
}
