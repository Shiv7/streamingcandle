package com.kotsin.consumer.quant.calculator;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.model.MTFDistribution;
import com.kotsin.consumer.model.EvolutionMetrics;
import com.kotsin.consumer.quant.config.QuantScoreConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PriceActionScoreCalculator - Calculates price action subscore (0-12 points).
 *
 * Evaluates:
 * - Candle sequence pattern (0-4)
 * - Wyckoff phase (0-4)
 * - PCR/OI divergence (0-4)
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PriceActionScoreCalculator {

    private final QuantScoreConfig config;

    /**
     * Calculate price action subscore
     *
     * @param family FamilyCandle with MTF evolution data
     * @return Score 0-12
     */
    public double calculate(FamilyCandle family) {
        double maxScore = config.getWeight().getPriceAction();

        MTFDistribution mtf = family.getMtfDistribution();
        if (mtf == null || mtf.getEvolution() == null) {
            // Fall back to basic price action
            return calculateBasicPriceActionScore(family);
        }

        EvolutionMetrics evo = mtf.getEvolution();

        double sequenceScore = calculateSequenceScore(evo);
        double wyckoffScore = calculateWyckoffScore(evo);
        double divergenceScore = calculateDivergenceScore(evo);

        return Math.min(maxScore, sequenceScore + wyckoffScore + divergenceScore);
    }

    /**
     * Calculate candle sequence pattern score (0-4)
     */
    private double calculateSequenceScore(EvolutionMetrics evo) {
        double maxPoints = 4.0;

        EvolutionMetrics.CandleSequence seq = evo.getCandleSequence();
        if (seq == null) {
            return 0;
        }

        String seqType = seq.getSequenceType() != null ? seq.getSequenceType().name() : null;
        double momentumSlope = seq.getMomentumSlope();

        // Strong momentum trend
        if (Math.abs(momentumSlope) > config.getPriceAction().getMomentumSlopeStrong()) {
            return maxPoints;
        }

        // Moderate momentum
        if (Math.abs(momentumSlope) > config.getPriceAction().getMomentumSlopeModerate()) {
            return maxPoints * 0.7;
        }

        // Check sequence type
        if (seqType != null) {
            switch (seqType) {
                case "TREND":
                    return maxPoints * 0.8;
                case "V_PATTERN":
                case "INVERTED_V":
                    return maxPoints * 0.7;  // Reversal patterns
                case "REVERSAL":
                    return maxPoints * 0.6;
                case "CHOP":
                default:
                    return maxPoints * 0.2;
            }
        }

        return maxPoints * 0.3;
    }

    /**
     * Calculate Wyckoff phase score (0-4)
     */
    private double calculateWyckoffScore(EvolutionMetrics evo) {
        double maxPoints = 4.0;

        EvolutionMetrics.WyckoffPhase wyckoff = evo.getWyckoffPhase();
        if (wyckoff == null) {
            return 0;
        }

        String phase = wyckoff.getPhase() != null ? wyckoff.getPhase().name() : null;
        double strength = wyckoff.getPhaseStrength();
        boolean transitioning = wyckoff.isPhaseTransition();

        if (phase == null) {
            return 0;
        }

        double baseScore = 0;
        switch (phase) {
            case "ACCUMULATION":
            case "MARKUP":
                baseScore = maxPoints * 0.9;  // Bullish phases
                break;
            case "DISTRIBUTION":
            case "MARKDOWN":
                baseScore = maxPoints * 0.9;  // Bearish phases (still actionable)
                break;
            default:
                baseScore = maxPoints * 0.3;
        }

        // Adjust for strength and transition
        double finalScore = baseScore * strength;

        // Transitioning phases are interesting
        if (transitioning) {
            finalScore *= 1.1;
        }

        return Math.min(maxPoints, finalScore);
    }

    /**
     * Calculate divergence score (0-4)
     */
    private double calculateDivergenceScore(EvolutionMetrics evo) {
        double maxPoints = 4.0;
        double score = 0;

        // PCR divergence
        EvolutionMetrics.PCREvolution pcrEvo = evo.getPcrEvolution();
        if (pcrEvo != null && pcrEvo.isPcrDivergence()) {
            double strength = pcrEvo.getDivergenceStrength();
            score += (maxPoints / 2) * Math.min(1.0, strength);
        }

        // OI divergence
        EvolutionMetrics.OIEvolution oiEvo = evo.getOiEvolution();
        if (oiEvo != null && oiEvo.isOiDivergence()) {
            score += maxPoints / 2;
        }

        return Math.min(maxPoints, score);
    }

    /**
     * Fallback basic price action score
     */
    private double calculateBasicPriceActionScore(FamilyCandle family) {
        double maxScore = config.getWeight().getPriceAction();

        double score = 0;

        // Check reversal signals
        if (family.isEquityShowingReversal()) {
            score += maxScore * 0.3;
        }

        if (family.isOiConfirmsReversal()) {
            score += maxScore * 0.3;
        }

        // Check directional bias
        String bias = family.getDirectionalBias();
        if ("STRONG_BULLISH".equals(bias) || "STRONG_BEARISH".equals(bias)) {
            score += maxScore * 0.3;
        } else if ("BULLISH".equals(bias) || "BEARISH".equals(bias)) {
            score += maxScore * 0.2;
        }

        return Math.min(maxScore, score);
    }
}
