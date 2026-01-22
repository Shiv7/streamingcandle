package com.kotsin.consumer.signal.service;

import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore;
import com.kotsin.consumer.enrichment.model.HistoricalContext;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * MicrostructureLeadingEdgeCalculator - Detects when lower timeframes lead higher timeframes.
 *
 * WHAT IS LEADING EDGE:
 * When institutions start accumulating/distributing, it shows in microstructure FIRST:
 * 1. LTF (1m/5m) OFI spikes → Institutions are active
 * 2. MTF (15m/30m) follows → Move is building
 * 3. HTF (1h+) catches up → Move is confirmed (too late for best entry)
 *
 * THE LEADING EDGE FORMULA:
 * LeadingEdgeScore = (LTF_Weight × LTF_Avg) + (MTF_Weight × MTF_Avg) + (HTF_Weight × HTF_Avg) + MomentumBonus
 *
 * WHERE:
 * - LTF_Weight = 0.45 (Highest - early detection)
 * - MTF_Weight = 0.35 (Confirmation building)
 * - HTF_Weight = 0.20 (Lagging confirmation)
 *
 * MOMENTUM BONUS:
 * - LTF > MTF > HTF: +15 (LEADING - best entry)
 * - LTF > MTF: +10 (EARLY - good entry)
 * - All similar: 0 (ALIGNED - late entry)
 * - HTF > LTF: -10 (REVERSAL_RISK - caution!)
 *
 * WHY THIS MATTERS FOR TRADING:
 * - LEADING pattern = Institutions entering, retail hasn't noticed
 * - REVERSAL_RISK pattern = HTF strong but LTF weakening = potential trap
 *
 * ALL METHODS ARE NULL-SAFE.
 */
@Slf4j
@Service
public class MicrostructureLeadingEdgeCalculator {

    // Timeframe weights
    private static final double LTF_WEIGHT = 0.45;  // 1m, 5m
    private static final double MTF_WEIGHT = 0.35;  // 15m, 30m
    private static final double HTF_WEIGHT = 0.20;  // 1h+

    // Momentum bonus values
    private static final int LEADING_BONUS = 15;
    private static final int EARLY_BONUS = 10;
    private static final int REVERSAL_PENALTY = -10;

    // OFI zscore thresholds for activity detection
    private static final double OFI_ACTIVE_THRESHOLD = 1.0;
    private static final double OFI_STRONG_THRESHOLD = 1.5;
    private static final double OFI_EXTREME_THRESHOLD = 2.0;

    /**
     * Calculate leading edge from enriched score.
     *
     * @param score The enriched quant score
     * @return LeadingEdgeResult with pattern and score
     */
    public LeadingEdgeResult calculate(EnrichedQuantScore score) {
        if (score == null) {
            return LeadingEdgeResult.none();
        }

        // Extract microstructure data for different timeframes
        // Using OFI zscore as primary microstructure indicator
        double ltfScore = getLtfMicrostructure(score);
        double mtfScore = getMtfMicrostructure(score);
        double htfScore = getHtfMicrostructure(score);

        // Calculate weighted base score
        double baseScore = (LTF_WEIGHT * ltfScore) + (MTF_WEIGHT * mtfScore) + (HTF_WEIGHT * htfScore);

        // Determine pattern and momentum bonus
        LeadingEdgePattern pattern = determinePattern(ltfScore, mtfScore, htfScore);
        int momentumBonus = getMomentumBonus(pattern);

        // Final score
        double finalScore = Math.max(0, Math.min(100, baseScore + momentumBonus));

        // Determine direction based on OFI
        String direction = determineDirection(score);

        LeadingEdgeResult result = LeadingEdgeResult.builder()
                .detected(finalScore > 30) // Only detected if score > 30
                .score(finalScore)
                .ltfScore(ltfScore)
                .mtfScore(mtfScore)
                .htfScore(htfScore)
                .pattern(pattern)
                .momentumBonus(momentumBonus)
                .direction(direction)
                .build();

        if (result.isDetected()) {
            log.debug("[LEADING_EDGE] {} | LTF={:.0f} | MTF={:.0f} | HTF={:.0f} | bonus={} | score={:.0f}",
                    pattern, ltfScore, mtfScore, htfScore, momentumBonus, finalScore);
        }

        return result;
    }

    /**
     * Check if there's a bullish leading edge (good for LONG entry).
     */
    public boolean hasBullishLeadingEdge(EnrichedQuantScore score) {
        LeadingEdgeResult result = calculate(score);
        return result.isDetected() &&
                result.getPattern() == LeadingEdgePattern.LEADING &&
                "BULLISH".equals(result.getDirection());
    }

    /**
     * Check if there's a bearish leading edge (good for SHORT entry).
     */
    public boolean hasBearishLeadingEdge(EnrichedQuantScore score) {
        LeadingEdgeResult result = calculate(score);
        return result.isDetected() &&
                result.getPattern() == LeadingEdgePattern.LEADING &&
                "BEARISH".equals(result.getDirection());
    }

    /**
     * Check if there's reversal risk (HTF > LTF).
     */
    public boolean hasReversalRisk(EnrichedQuantScore score) {
        LeadingEdgeResult result = calculate(score);
        return result.isDetected() && result.getPattern() == LeadingEdgePattern.REVERSAL_RISK;
    }

    /**
     * Get LTF (1m/5m) microstructure score.
     * Uses current OFI zscore as LTF indicator.
     */
    private double getLtfMicrostructure(EnrichedQuantScore score) {
        // Try to get from historical context (OFI is calculated on incoming candles = LTF)
        HistoricalContext hist = score.getHistoricalContext();
        if (hist != null && hist.getOfiContext() != null) {
            double zscore = hist.getOfiContext().getZscore();
            // Convert zscore to 0-100 scale
            // zscore of 2 = 100, zscore of 0 = 50, zscore of -2 = 0
            return Math.max(0, Math.min(100, 50 + (zscore * 25)));
        }

        // Fallback: use microstructure score from base score
        if (score.getBaseScore() != null && score.getBaseScore().getBreakdown() != null) {
            double microScore = score.getBaseScore().getBreakdown().getMicrostructureScore();
            if (microScore > 0) {
                // Microstructure is 0-18, convert to 0-100
                return (microScore / 18.0) * 100;
            }
        }

        return 50; // Neutral if no data
    }

    /**
     * Get MTF (15m/30m) microstructure score.
     * Uses rolling average of OFI as MTF indicator.
     */
    private double getMtfMicrostructure(EnrichedQuantScore score) {
        // Try to get from historical context percentile (represents medium-term position)
        HistoricalContext hist = score.getHistoricalContext();
        if (hist != null && hist.getOfiContext() != null) {
            double percentile = hist.getOfiContext().getPercentile();
            // Percentile is 0-100, use directly
            return percentile;
        }

        // Fallback: slight decay from LTF (MTF typically lags)
        double ltf = getLtfMicrostructure(score);
        return 50 + (ltf - 50) * 0.7; // 70% of LTF movement
    }

    /**
     * Get HTF (1h+) microstructure score.
     * Uses dominant regime as HTF indicator.
     */
    private double getHtfMicrostructure(EnrichedQuantScore score) {
        // Try to get from MTF SMC context
        if (score.getMtfSmcContext() != null) {
            var smc = score.getMtfSmcContext();

            // Use HTF bias as score
            if (smc.getHtfBias() != null) {
                return switch (smc.getHtfBias()) {
                    case BULLISH -> 75;
                    case BEARISH -> 25;
                    case RANGING -> 50;
                    case UNKNOWN -> 50;
                };
            }
        }

        // Fallback: use historical dominant regime
        HistoricalContext hist = score.getHistoricalContext();
        if (hist != null && hist.getDominantRegime() != null) {
            return switch (hist.getDominantRegime()) {
                case STRONG_POSITIVE -> 90;
                case POSITIVE -> 70;
                case NEUTRAL -> 50;
                case NEGATIVE -> 30;
                case STRONG_NEGATIVE -> 10;
            };
        }

        // Fallback: further decay from MTF
        double mtf = getMtfMicrostructure(score);
        return 50 + (mtf - 50) * 0.5; // 50% of MTF movement
    }

    /**
     * Determine the leading edge pattern.
     */
    private LeadingEdgePattern determinePattern(double ltf, double mtf, double htf) {
        double ltfMtfDiff = ltf - mtf;
        double mtfHtfDiff = mtf - htf;
        double ltfHtfDiff = ltf - htf;

        // LEADING: LTF > MTF > HTF (classic early entry pattern)
        if (ltf > mtf && mtf > htf && ltfHtfDiff > 10) {
            return LeadingEdgePattern.LEADING;
        }

        // EARLY: LTF > MTF (move starting, HTF may be similar or lower)
        if (ltf > mtf && ltfMtfDiff > 10) {
            return LeadingEdgePattern.EARLY;
        }

        // REVERSAL_RISK: HTF > MTF > LTF or HTF >> LTF (potential reversal)
        if (htf > mtf && htf > ltf && (htf - ltf) > 15) {
            return LeadingEdgePattern.REVERSAL_RISK;
        }

        // WEAK: All below 30 (no momentum)
        if (ltf < 30 && mtf < 30 && htf < 30) {
            return LeadingEdgePattern.WEAK;
        }

        // ALIGNED: All similar (move in progress, possibly late)
        return LeadingEdgePattern.ALIGNED;
    }

    /**
     * Get momentum bonus for pattern.
     */
    private int getMomentumBonus(LeadingEdgePattern pattern) {
        return switch (pattern) {
            case LEADING -> LEADING_BONUS;
            case EARLY -> EARLY_BONUS;
            case REVERSAL_RISK -> REVERSAL_PENALTY;
            case ALIGNED, WEAK -> 0;
        };
    }

    /**
     * Determine direction from microstructure.
     */
    private String determineDirection(EnrichedQuantScore score) {
        HistoricalContext hist = score.getHistoricalContext();
        if (hist != null && hist.getOfiContext() != null) {
            double zscore = hist.getOfiContext().getZscore();
            if (zscore > OFI_ACTIVE_THRESHOLD) {
                return "BULLISH";
            } else if (zscore < -OFI_ACTIVE_THRESHOLD) {
                return "BEARISH";
            }
        }

        // Fallback to MTF SMC context
        if (score.getMtfSmcContext() != null && score.getMtfSmcContext().getHtfBias() != null) {
            return switch (score.getMtfSmcContext().getHtfBias()) {
                case BULLISH -> "BULLISH";
                case BEARISH -> "BEARISH";
                default -> "NEUTRAL";
            };
        }

        return "NEUTRAL";
    }

    /**
     * Leading edge patterns.
     */
    public enum LeadingEdgePattern {
        /**
         * LTF > MTF > HTF - Classic institutional entry pattern.
         * Best entry timing!
         */
        LEADING,

        /**
         * LTF > MTF - Move is starting, good entry.
         */
        EARLY,

        /**
         * All timeframes aligned - Move in progress, may be late.
         */
        ALIGNED,

        /**
         * HTF > LTF - Higher timeframes strong but LTF weakening.
         * Potential reversal - CAUTION!
         */
        REVERSAL_RISK,

        /**
         * All timeframes weak - No momentum.
         */
        WEAK
    }

    /**
     * LeadingEdgeResult - Result of leading edge calculation.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LeadingEdgeResult {

        /**
         * Is meaningful leading edge detected?
         */
        @Builder.Default
        private boolean detected = false;

        /**
         * Overall leading edge score (0-100)
         */
        @Builder.Default
        private double score = 0;

        /**
         * LTF microstructure score (0-100)
         */
        @Builder.Default
        private double ltfScore = 50;

        /**
         * MTF microstructure score (0-100)
         */
        @Builder.Default
        private double mtfScore = 50;

        /**
         * HTF microstructure score (0-100)
         */
        @Builder.Default
        private double htfScore = 50;

        /**
         * Detected pattern
         */
        @Builder.Default
        private LeadingEdgePattern pattern = LeadingEdgePattern.ALIGNED;

        /**
         * Momentum bonus/penalty applied
         */
        @Builder.Default
        private int momentumBonus = 0;

        /**
         * Direction (BULLISH, BEARISH, NEUTRAL)
         */
        @Builder.Default
        private String direction = "NEUTRAL";

        /**
         * Create empty (no detection) result.
         */
        public static LeadingEdgeResult none() {
            return LeadingEdgeResult.builder()
                    .detected(false)
                    .score(0)
                    .pattern(LeadingEdgePattern.WEAK)
                    .direction("NEUTRAL")
                    .build();
        }

        /**
         * Is this a good entry pattern (LEADING or EARLY)?
         */
        public boolean isGoodEntry() {
            return detected && (pattern == LeadingEdgePattern.LEADING || pattern == LeadingEdgePattern.EARLY);
        }

        /**
         * Is this a caution pattern?
         */
        public boolean isCautionPattern() {
            return detected && pattern == LeadingEdgePattern.REVERSAL_RISK;
        }

        /**
         * Get confidence modifier based on pattern.
         * LEADING: +15%, EARLY: +10%, ALIGNED: 0%, REVERSAL_RISK: -20%
         */
        public double getConfidenceModifier() {
            if (!detected) return 1.0;
            return switch (pattern) {
                case LEADING -> 1.15;
                case EARLY -> 1.10;
                case ALIGNED -> 1.0;
                case REVERSAL_RISK -> 0.80;
                case WEAK -> 0.90;
            };
        }
    }
}
