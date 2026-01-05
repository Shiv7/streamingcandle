package com.kotsin.consumer.quant.calculator;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.OptionCandle;
import com.kotsin.consumer.model.MTFDistribution;
import com.kotsin.consumer.model.EvolutionMetrics;
import com.kotsin.consumer.quant.config.QuantScoreConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * VolumeProfileScoreCalculator - Calculates volume profile subscore (0-8 points).
 *
 * Evaluates:
 * - POC migration (0-4)
 * - Value area shift (0-4)
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class VolumeProfileScoreCalculator {

    private final QuantScoreConfig config;

    /**
     * Calculate volume profile subscore
     *
     * @param family FamilyCandle with volume profile data
     * @return Score 0-8
     */
    public double calculate(FamilyCandle family) {
        double maxScore = config.getWeight().getVolumeProfile();

        MTFDistribution mtf = family.getMtfDistribution();
        if (mtf == null || mtf.getEvolution() == null ||
            mtf.getEvolution().getVolumeProfileEvolution() == null) {
            // Fall back to option-level volume profile
            return calculateFromOptions(family);
        }

        EvolutionMetrics.VolumeProfileEvolution vol = mtf.getEvolution().getVolumeProfileEvolution();

        double pocScore = calculatePOCMigrationScore(vol);
        double vaScore = calculateValueAreaScore(vol);

        return Math.min(maxScore, pocScore + vaScore);
    }

    /**
     * Calculate POC migration score (0-4)
     */
    private double calculatePOCMigrationScore(EvolutionMetrics.VolumeProfileEvolution vol) {
        double maxPoints = 4.0;

        Double migration = vol.getPocMigration();
        if (migration == null) {
            return 0;
        }

        double threshold = config.getVolumeProfile().getPocMigrationSignificant();

        // Significant POC migration indicates trend
        if (Math.abs(migration) > threshold) {
            double normalized = Math.min(1.0, Math.abs(migration) / (threshold * 2));
            return maxPoints * (0.6 + 0.4 * normalized);
        }

        // Check POC trend
        String pocTrend = vol.getPocTrend() != null ? vol.getPocTrend().name() : null;
        if ("RISING".equals(pocTrend) || "FALLING".equals(pocTrend)) {
            return maxPoints * 0.5;
        }

        return maxPoints * 0.2;
    }

    /**
     * Calculate value area shift score (0-4)
     */
    private double calculateValueAreaScore(EvolutionMetrics.VolumeProfileEvolution vol) {
        double maxPoints = 4.0;

        boolean expanding = vol.isValueAreaExpanding();
        boolean contracting = vol.isValueAreaContracting();
        String shift = vol.getValueAreaShift() != null ? vol.getValueAreaShift().name() : null;

        double score = 0;

        // Contracting VA = conviction building
        if (contracting) {
            score += maxPoints * 0.4;
        }

        // Expanding VA = uncertainty (still tradeable)
        if (expanding) {
            score += maxPoints * 0.2;
        }

        // Directional shift
        if ("UPWARD".equals(shift) || "DOWNWARD".equals(shift)) {
            score += maxPoints * 0.4;
        }

        return Math.min(maxPoints, score);
    }

    /**
     * Fallback: Calculate from individual option volume profiles
     */
    private double calculateFromOptions(FamilyCandle family) {
        double maxScore = config.getWeight().getVolumeProfile();
        List<OptionCandle> options = family.getOptions();

        if (options == null || options.isEmpty()) {
            return 0;
        }

        double totalPOC = 0;
        double totalVAH = 0;
        double totalVAL = 0;
        int count = 0;

        for (OptionCandle opt : options) {
            if (opt != null && opt.getPoc() > 0) {
                totalPOC += opt.getPoc();
                totalVAH += opt.getVah();
                totalVAL += opt.getVal();
                count++;
            }
        }

        if (count == 0) {
            return 0;
        }

        double avgPOC = totalPOC / count;
        double avgVAH = totalVAH / count;
        double avgVAL = totalVAL / count;

        // Check if price is near key levels
        var primary = family.getPrimaryInstrumentOrFallback();
        if (primary == null) {
            return maxScore * 0.3;
        }

        double price = primary.getClose();
        double vaRange = avgVAH - avgVAL;

        if (vaRange <= 0) {
            return maxScore * 0.3;
        }

        // Score based on position relative to value area
        if (price > avgVAH) {
            // Above value area - breakout potential
            return maxScore * 0.7;
        } else if (price < avgVAL) {
            // Below value area - breakdown potential
            return maxScore * 0.7;
        } else {
            // Inside value area - consolidation
            double posInVA = (price - avgVAL) / vaRange;
            // Near edges are more interesting
            double edgeProximity = Math.abs(posInVA - 0.5) * 2;
            return maxScore * (0.3 + 0.4 * edgeProximity);
        }
    }
}
