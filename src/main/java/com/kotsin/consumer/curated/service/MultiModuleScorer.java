package com.kotsin.consumer.curated.service;

import com.kotsin.consumer.capital.model.FinalMagnitude;
import com.kotsin.consumer.curated.model.CuratedSignal;
import com.kotsin.consumer.curated.model.FuturesOptionsAlignment;
import com.kotsin.consumer.curated.model.MultiTFBreakout;
import com.kotsin.consumer.curated.model.MultiTimeframeLevels;
import com.kotsin.consumer.model.IPUOutput;
import com.kotsin.consumer.model.MTVCPOutput;
import com.kotsin.consumer.regime.model.ACLOutput;
import com.kotsin.consumer.regime.model.IndexRegime;
import com.kotsin.consumer.regime.model.SecurityRegime;
import com.kotsin.consumer.signal.model.CSSOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * MultiModuleScorer - ENHANCED - Calculates final curated score using all 16 modules + F&O + Levels
 *
 * Score Breakdown (0-100 points):
 * - Structure Score (0-30): Multi-TF confluence, compression quality, runway
 * - Regime Score (0-20): Index regime + Security regime
 * - Flow Score (0-15): IPU score + X-factor
 * - Microstructure Score (0-10): Volume Z-score, Kyle's Lambda, OFI, VPIN
 * - F&O Score (0-15): Futures + Options alignment 🆕
 * - Levels Score (0-10): Near Fibonacci/Pivot levels 🆕
 *
 * Multipliers Applied:
 * - ACL multiplier (0.7-1.1): Trend age adjustment
 * - CSS multiplier (0.9-1.1): Structure quality adjustment
 * - F&O multiplier (0.7-1.3): F&O alignment boost 🆕
 */
@Service
public class MultiModuleScorer {

    private static final Logger log = LoggerFactory.getLogger(MultiModuleScorer.class);

    /**
     * ENHANCED - Calculate curated score using all modules + F&O + Levels
     */
    public double calculateCuratedScore(
            MultiTFBreakout breakout,
            IndexRegime indexRegime,
            SecurityRegime securityRegime,
            ACLOutput acl,
            MTVCPOutput vcp,
            CSSOutput css,
            IPUOutput ipu,
            FuturesOptionsAlignment foAlignment,
            MultiTimeframeLevels levels,
            double currentPrice) {

        // STRUCTURE SCORE (0-30 points) - Reduced from 40
        double structureScore = calculateStructureScore(breakout, vcp);

        // REGIME SCORE (0-20 points) - Reduced from 25
        double regimeScore = calculateRegimeScore(indexRegime, securityRegime);

        // FLOW SCORE (0-15 points) - Reduced from 20
        double flowScore = calculateFlowScore(ipu);

        // MICROSTRUCTURE SCORE (0-10 points) - Reduced from 15
        double microScore = calculateMicrostructureScore(breakout);

        // F&O SCORE (0-15 points) 🆕
        double foScore = calculateFOScore(foAlignment);

        // LEVELS SCORE (0-10 points) 🆕
        double levelsScore = calculateLevelsScore(levels, currentPrice);

        // Sum base scores
        double baseScore = structureScore + regimeScore + flowScore + microScore + foScore + levelsScore;

        // Apply multipliers
        double aclMultiplier = acl != null ? acl.getAclMultiplier() : 1.0;
        double cssMultiplier = css != null ? getCSSMultiplier(css) : 1.0;
        double foMultiplier = getFOMultiplier(foAlignment);

        double finalScore = baseScore * aclMultiplier * cssMultiplier * foMultiplier;

        // Cap at 100
        finalScore = Math.min(finalScore, 100.0);

        log.info("📊 ENHANCED Score: Structure={} Regime={} Flow={} Micro={} F&O={} Levels={} | ACL={} CSS={} FO={} | Final={}",
                String.format("%.1f", structureScore),
                String.format("%.1f", regimeScore),
                String.format("%.1f", flowScore),
                String.format("%.1f", microScore),
                String.format("%.1f", foScore),
                String.format("%.1f", levelsScore),
                String.format("%.2f", aclMultiplier),
                String.format("%.2f", cssMultiplier),
                String.format("%.2f", foMultiplier),
                String.format("%.1f", finalScore));

        return finalScore;
    }

    /**
     * Calculate structure score (0-30 points) - UPDATED
     */
    private double calculateStructureScore(MultiTFBreakout breakout, MTVCPOutput vcp) {
        double score = 0.0;

        // Multi-TF confluence (0-12 points)
        score += breakout.getConfluenceScore() * 12.0;

        // Compression quality (0-12 points)
        if (breakout.getPrimaryBreakout().getCompressionRatio() < 1.5) {
            score += 12.0;  // Very tight coil
        } else if (breakout.getPrimaryBreakout().getCompressionRatio() < 2.0) {
            score += 8.0;   // Moderate coil
        } else {
            score += 4.0;   // Loose coil
        }

        // Runway score (0-6 points) - Lower VCP = cleaner runway
        if (vcp != null) {
            double runwayPoints = (1.0 - vcp.getVcpCombinedScore()) * 6.0;
            score += runwayPoints;
        }

        return Math.min(score, 30.0);
    }

    /**
     * Calculate regime score (0-20 points) - UPDATED
     */
    private double calculateRegimeScore(IndexRegime indexRegime, SecurityRegime securityRegime) {
        double score = 0.0;

        // Index regime (0-12 points)
        if (indexRegime != null) {
            double indexScore = indexRegime.getRegimeStrength() * indexRegime.getRegimeCoherence();
            score += indexScore * 12.0;
        }

        // Security regime (0-8 points)
        if (securityRegime != null) {
            score += securityRegime.getFinalRegimeScore() * 8.0;
        }

        return Math.min(score, 20.0);
    }

    /**
     * Calculate flow score (0-15 points) - UPDATED
     */
    private double calculateFlowScore(IPUOutput ipu) {
        double score = 0.0;

        if (ipu == null) {
            return 0.0;
        }

        // IPU final score (0-8 points)
        score += ipu.getFinalIpuScore() * 8.0;

        // X-factor flag (0-7 points)
        if (ipu.isXfactorFlag()) {
            score += 7.0;
        }

        return Math.min(score, 15.0);
    }

    /**
     * Calculate microstructure score (0-10 points) - UPDATED
     */
    private double calculateMicrostructureScore(MultiTFBreakout breakout) {
        double score = 0.0;

        double avgVolumeZ = breakout.getAvgVolumeZScore();
        double avgKyle = breakout.getAvgKyleLambda();
        double ofi = breakout.getPrimaryBreakout().getOfi();
        double vpin = breakout.getPrimaryBreakout().getVpin();

        // Volume Z-score (0-4 points)
        double volumePoints = Math.min(avgVolumeZ / 2.0, 1.0) * 4.0;
        score += volumePoints;

        // Kyle's Lambda (0-3 points)
        score += (avgKyle > 0.5) ? 3.0 : (avgKyle * 6.0);

        // OFI (0-2 points)
        score += (ofi > 0) ? 2.0 : 0.0;

        // VPIN (0-1 point)
        score += (vpin > 0.5) ? 1.0 : 0.0;

        return Math.min(score, 10.0);
    }

    /**
     * Calculate F&O score (0-15 points) 🆕
     */
    private double calculateFOScore(FuturesOptionsAlignment foAlignment) {
        if (foAlignment == null || !foAlignment.isUsable()) {
            return 0.0;  // No penalty if F&O data not available
        }

        double score = 0.0;

        // Direct alignment score (0-15 points)
        score = foAlignment.getAlignmentScore() * 15.0;

        return Math.min(score, 15.0);
    }

    /**
     * Calculate Levels score (0-10 points) 🆕
     */
    private double calculateLevelsScore(MultiTimeframeLevels levels, double currentPrice) {
        if (levels == null) {
            return 0.0;  // No penalty if levels not available
        }

        double score = 0.0;

        // Check if near significant level (0-10 points)
        if (levels.isNearSignificantLevel(currentPrice)) {
            score += 10.0;  // Full points if at key level (ideal entry)
        } else {
            // Partial points based on distance to nearest level
            double nearestSupport = levels.getNearestSupport(currentPrice);
            double nearestResistance = levels.getNearestResistance(currentPrice);

            if (nearestSupport > 0) {
                double distancePercent = ((currentPrice - nearestSupport) / currentPrice) * 100;
                if (distancePercent < 1.0) {  // Within 1% of support
                    score += 5.0;
                } else if (distancePercent < 2.0) {  // Within 2% of support
                    score += 3.0;
                }
            }

            if (nearestResistance > 0) {
                double distancePercent = ((nearestResistance - currentPrice) / currentPrice) * 100;
                if (distancePercent < 1.0) {  // Within 1% of resistance
                    score += 5.0;
                } else if (distancePercent < 2.0) {  // Within 2% of resistance
                    score += 3.0;
                }
            }
        }

        return Math.min(score, 10.0);
    }

    /**
     * Get CSS multiplier based on structure state
     */
    private double getCSSMultiplier(CSSOutput css) {
        if (css.getCssScore() < 0.5) {
            return 1.1;  // Clean runway, boost signal
        } else if (css.getCssScore() > 0.75) {
            return 0.9;  // Heavy structure, reduce signal
        }
        return 1.0;  // Neutral
    }

    /**
     * Get F&O multiplier based on alignment 🆕
     */
    private double getFOMultiplier(FuturesOptionsAlignment foAlignment) {
        if (foAlignment == null || !foAlignment.isUsable()) {
            return 1.0;  // Neutral if no F&O data
        }

        if (!foAlignment.isAligned()) {
            return 0.7;  // Reduce score if F&O not aligned
        }

        // Strong alignment boost
        if (foAlignment.getBias() == FuturesOptionsAlignment.DirectionalBias.STRONG_BULLISH) {
            return 1.3;
        } else if (foAlignment.getBias() == FuturesOptionsAlignment.DirectionalBias.BULLISH) {
            return 1.15;
        }

        return 1.0;  // Neutral alignment
    }

    /**
     * Build score breakdown for transparency
     */
    public CuratedSignal.ScoreBreakdown buildScoreBreakdown(
            double structureScore,
            double regimeScore,
            double flowScore,
            double microScore,
            double aclMultiplier,
            double cssMultiplier,
            double totalScore) {

        return CuratedSignal.ScoreBreakdown.builder()
                .structureScore(structureScore)
                .regimeScore(regimeScore)
                .flowScore(flowScore)
                .microScore(microScore)
                .aclMultiplier(aclMultiplier)
                .cssMultiplier(cssMultiplier)
                .totalScore(totalScore)
                .build();
    }

    /**
     * Calculate position size multiplier based on score AND F&O alignment 🆕
     */
    public double calculatePositionSizeMultiplier(double curatedScore, FuturesOptionsAlignment foAlignment) {
        double baseMultiplier;

        if (curatedScore >= 80) {
            baseMultiplier = 1.5;      // High conviction
        } else if (curatedScore >= 60) {
            baseMultiplier = 1.0;      // Normal
        } else {
            baseMultiplier = 0.7;      // Lower conviction
        }

        // Apply F&O adjustment
        if (foAlignment != null && foAlignment.isUsable()) {
            double foAdjustment = foAlignment.getPositionSizeMultiplier();
            baseMultiplier *= foAdjustment;
        }

        return baseMultiplier;
    }
}
