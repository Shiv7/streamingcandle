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

    // ENHANCED: Lower thresholds to be more realistic
    private static final double STRONG_THRESHOLD = 50.0;  // % of max to consider "strong"
    private static final double MODERATE_THRESHOLD = 30.0;  // % of max to consider "moderate"

    /**
     * Calculate confluence subscore based on category agreement
     *
     * ENHANCED: Uses tiered scoring instead of binary threshold.
     * Categories can contribute partially to confluence.
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

        // ENHANCED: Calculate weighted confluence using tiered scoring
        double totalConfluence = calculateTieredConfluence(breakdown);

        // Scale to max score (totalConfluence is 0-7)
        double baseScore = maxScore * (totalConfluence / 7.0);

        // Count categories with any meaningful data
        int strongCategories = countStrongCategories(breakdown);
        int moderateCategories = countModerateCategories(breakdown);

        // Bonus for high concentration of strong signals
        if (strongCategories >= 5) {
            baseScore *= 1.3;  // 30% bonus for 5+ strong categories
        } else if (strongCategories >= 3) {
            baseScore *= 1.15;  // 15% bonus for 3-4 strong categories
        } else if (moderateCategories >= 4) {
            baseScore *= 1.05;  // 5% bonus for moderate agreement
        }

        // Only apply penalty for truly no confluence (< 2 moderate categories)
        if (strongCategories == 0 && moderateCategories < 2) {
            baseScore *= 0.5;
        }

        // Check for consistency in scores (low variance = higher confluence)
        double variance = calculateScoreVariance(breakdown);
        if (variance < 200) {
            baseScore *= 1.1;  // Low variance bonus
        } else if (variance > 600) {
            baseScore *= 0.9;  // High variance penalty
        }

        return Math.min(maxScore, baseScore);
    }

    /**
     * Calculate tiered confluence score
     * Each category contributes: 1.0 if strong, 0.5 if moderate, 0 otherwise
     */
    private double calculateTieredConfluence(QuantScoreBreakdown breakdown) {
        double total = 0;

        total += getTieredScore(breakdown.getGreeksPct());
        total += getTieredScore(breakdown.getIvSurfacePct());
        total += getTieredScore(breakdown.getMicrostructurePct());
        total += getTieredScore(breakdown.getOptionsFlowPct());
        total += getTieredScore(breakdown.getPriceActionPct());
        total += getTieredScore(breakdown.getVolumeProfilePct());
        total += getTieredScore(breakdown.getCrossInstrumentPct());

        return total;
    }

    /**
     * Get tiered score contribution for a category
     */
    private double getTieredScore(double pct) {
        if (pct >= STRONG_THRESHOLD) return 1.0;
        if (pct >= MODERATE_THRESHOLD) return 0.5;
        if (pct > 10) return 0.2;  // Some data available
        return 0;
    }

    /**
     * Count categories above strong threshold
     */
    private int countStrongCategories(QuantScoreBreakdown breakdown) {
        int count = 0;

        if (breakdown.getGreeksPct() >= STRONG_THRESHOLD) count++;
        if (breakdown.getIvSurfacePct() >= STRONG_THRESHOLD) count++;
        if (breakdown.getMicrostructurePct() >= STRONG_THRESHOLD) count++;
        if (breakdown.getOptionsFlowPct() >= STRONG_THRESHOLD) count++;
        if (breakdown.getPriceActionPct() >= STRONG_THRESHOLD) count++;
        if (breakdown.getVolumeProfilePct() >= STRONG_THRESHOLD) count++;
        if (breakdown.getCrossInstrumentPct() >= STRONG_THRESHOLD) count++;

        return count;
    }

    /**
     * Count categories above moderate threshold
     */
    private int countModerateCategories(QuantScoreBreakdown breakdown) {
        int count = 0;

        if (breakdown.getGreeksPct() >= MODERATE_THRESHOLD) count++;
        if (breakdown.getIvSurfacePct() >= MODERATE_THRESHOLD) count++;
        if (breakdown.getMicrostructurePct() >= MODERATE_THRESHOLD) count++;
        if (breakdown.getOptionsFlowPct() >= MODERATE_THRESHOLD) count++;
        if (breakdown.getPriceActionPct() >= MODERATE_THRESHOLD) count++;
        if (breakdown.getVolumeProfilePct() >= MODERATE_THRESHOLD) count++;
        if (breakdown.getCrossInstrumentPct() >= MODERATE_THRESHOLD) count++;

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
