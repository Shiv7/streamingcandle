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
 * DivergenceStrengthCalculator - Calculates the strength of price/indicator divergences.
 *
 * DIVERGENCE STRENGTH FORMULA:
 * Strength = BaseStrength × DurationFactor × MagnitudeFactor × VelocityFactor × ConfluenceFactor
 *
 * WHY DIVERGENCE STRENGTH MATTERS:
 * - Weak divergence (2 candles, 10% PCR) → Low probability reversal
 * - Strong divergence (8 candles, 35% PCR+OI) → High probability reversal
 *
 * TYPES OF DIVERGENCE:
 * 1. PCR Divergence: Price making new highs, PCR increasing (bearish)
 * 2. OI Divergence: Price making new highs, OI building shorts (bearish)
 * 3. Volume Divergence: Price making new highs, volume declining
 *
 * ALL METHODS ARE NULL-SAFE:
 * - If data unavailable, return DivergenceStrength with score=0
 * - Flow always continues
 */
@Slf4j
@Service
public class DivergenceStrengthCalculator {

    // Base strength when divergence is detected
    private static final double BASE_STRENGTH = 50.0;

    // Factor limits
    private static final double MAX_DURATION_FACTOR = 1.5;
    private static final double MAX_MAGNITUDE_FACTOR = 1.5;
    private static final double MAX_VELOCITY_FACTOR = 1.2;
    private static final double MIN_VELOCITY_FACTOR = 0.7;
    private static final double MAX_CONFLUENCE_FACTOR = 1.5;
    private static final double MIN_CONFLUENCE_FACTOR = 0.5;

    /**
     * Calculate divergence strength from enriched score.
     *
     * @param score The enriched quant score
     * @return DivergenceStrength with score 0-100 and factors
     */
    public DivergenceStrength calculate(EnrichedQuantScore score) {
        if (score == null) {
            return DivergenceStrength.none();
        }

        // GRACEFUL_DEGRADATION: Check divergence from historical context signals
        boolean hasPcrDivergence = false;
        boolean hasOiDivergence = false;
        HistoricalContext hist = score.getHistoricalContext();

        if (hist != null) {
            // Use bullish/bearish flips as proxy for PCR divergence
            hasPcrDivergence = hist.hasBullishFlip() || hist.hasBearishFlip();

            // Use absorption/exhaustion as proxy for OI divergence
            hasOiDivergence = hist.isAbsorptionDetected() || hist.isSellingExhaustion() || hist.isBuyingExhaustion();
        }

        if (!hasPcrDivergence && !hasOiDivergence) {
            return DivergenceStrength.none();
        }

        // Calculate individual factors
        double durationFactor = calculateDurationFactor(hist);
        double magnitudeFactor = calculateMagnitudeFactor(hist);
        double velocityFactor = calculateVelocityFactor(hist);
        double confluenceFactor = calculateConfluenceFactor(hasPcrDivergence, hasOiDivergence);

        // Calculate final strength
        double strength = BASE_STRENGTH * durationFactor * magnitudeFactor * velocityFactor * confluenceFactor;

        // Cap at 100
        strength = Math.min(100, strength);

        // Determine divergence type and direction
        String type = determineDivergenceType(hasPcrDivergence, hasOiDivergence);
        String direction = determineDivergenceDirection(hist);

        DivergenceStrength result = DivergenceStrength.builder()
                .detected(true)
                .score(strength)
                .baseStrength(BASE_STRENGTH)
                .durationFactor(durationFactor)
                .magnitudeFactor(magnitudeFactor)
                .velocityFactor(velocityFactor)
                .confluenceFactor(confluenceFactor)
                .hasPcrDivergence(hasPcrDivergence)
                .hasOiDivergence(hasOiDivergence)
                .type(type)
                .direction(direction)
                .build();

        log.debug("[DIV_STRENGTH] type={} | dir={} | score={:.1f} | dur={:.2f} | mag={:.2f} | vel={:.2f} | conf={:.2f}",
                type, direction, strength, durationFactor, magnitudeFactor, velocityFactor, confluenceFactor);

        return result;
    }

    /**
     * Calculate duration factor.
     * Longer divergence = stronger signal.
     *
     * Formula: min(1.5, 1 + (candleCount / 10))
     *
     * Examples:
     * - 2 candles → 1.2
     * - 5 candles → 1.5 (capped)
     * - 10 candles → 1.5 (capped)
     */
    private double calculateDurationFactor(HistoricalContext hist) {
        // Try to get divergence duration from historical context
        // Using OFI consecutive count as proxy for divergence duration
        int candleCount = 0;

        if (hist.getOfiContext() != null) {
            candleCount = Math.max(candleCount, hist.getOfiContext().getConsecutiveCount());
        }

        // Default to minimum if no data
        if (candleCount <= 0) {
            candleCount = 2; // Minimum for divergence
        }

        double factor = 1.0 + (candleCount / 10.0);
        return Math.min(MAX_DURATION_FACTOR, factor);
    }

    /**
     * Calculate magnitude factor.
     * Larger divergence = stronger signal.
     *
     * Formula: min(1.5, 1 + (divergencePct / 30))
     *
     * Examples:
     * - 10% divergence → 1.33
     * - 30% divergence → 1.5 (capped)
     * - 50% divergence → 1.5 (capped)
     */
    private double calculateMagnitudeFactor(HistoricalContext hist) {
        double divergencePct = 0;

        // Try to get from OFI zscore as proxy for magnitude
        if (hist.getOfiContext() != null) {
            double zscore = Math.abs(hist.getOfiContext().getZscore());
            // Convert zscore to percentage (rough conversion)
            divergencePct = zscore * 15; // 1 zscore ≈ 15% divergence
        }

        // Default if no data
        if (divergencePct <= 0) {
            divergencePct = 10; // Minimum meaningful divergence
        }

        double factor = 1.0 + (divergencePct / 30.0);
        return Math.min(MAX_MAGNITUDE_FACTOR, factor);
    }

    /**
     * Calculate velocity factor.
     * Accelerating divergence = stronger signal.
     * Decelerating divergence = weaker signal.
     *
     * Values:
     * - Accelerating: 1.2
     * - Stable: 1.0
     * - Decelerating: 0.7
     */
    private double calculateVelocityFactor(HistoricalContext hist) {
        // Check if momentum is building or exhausting
        if (hist.isMomentumBuilding()) {
            return MAX_VELOCITY_FACTOR; // Accelerating
        }

        if (hist.isSellingExhaustion() || hist.isBuyingExhaustion()) {
            return MIN_VELOCITY_FACTOR; // Decelerating/exhausting
        }

        // Check for informed flow as sign of acceleration
        if (hist.isInformedFlowActive() && hist.getInformedFlowIntensity() > 0.5) {
            return 1.1; // Slight acceleration
        }

        return 1.0; // Stable
    }

    /**
     * Calculate confluence factor.
     * Multiple divergence types = stronger signal.
     *
     * Formula: (divergenceTypes / 2) + 0.5
     *
     * Values:
     * - 0 types: 0.5 (minimum)
     * - 1 type (PCR or OI): 1.0
     * - 2 types (PCR + OI): 1.5 (maximum)
     */
    private double calculateConfluenceFactor(boolean hasPcrDivergence, boolean hasOiDivergence) {
        int divergenceTypes = 0;
        if (hasPcrDivergence) divergenceTypes++;
        if (hasOiDivergence) divergenceTypes++;

        double factor = (divergenceTypes / 2.0) + 0.5;
        return Math.max(MIN_CONFLUENCE_FACTOR, Math.min(MAX_CONFLUENCE_FACTOR, factor));
    }

    /**
     * Determine divergence type string.
     */
    private String determineDivergenceType(boolean hasPcr, boolean hasOi) {
        if (hasPcr && hasOi) {
            return "PCR+OI";
        } else if (hasPcr) {
            return "PCR";
        } else if (hasOi) {
            return "OI";
        }
        return "NONE";
    }

    /**
     * Determine divergence direction (BULLISH/BEARISH).
     */
    private String determineDivergenceDirection(HistoricalContext hist) {
        // Use OFI context to determine direction
        if (hist.getOfiContext() != null) {
            double zscore = hist.getOfiContext().getZscore();
            // Negative zscore with divergence = bullish divergence (sellers exhausted)
            // Positive zscore with divergence = bearish divergence (buyers exhausted)
            if (zscore < -1.0) {
                return "BULLISH";
            } else if (zscore > 1.0) {
                return "BEARISH";
            }
        }

        // Use dominant regime
        if (hist.getDominantRegime() != null) {
            return switch (hist.getDominantRegime()) {
                case STRONG_NEGATIVE, NEGATIVE -> "BULLISH"; // Divergence against bearish = bullish reversal
                case STRONG_POSITIVE, POSITIVE -> "BEARISH"; // Divergence against bullish = bearish reversal
                default -> "NEUTRAL";
            };
        }

        return "NEUTRAL";
    }

    /**
     * Check if divergence is strong enough for a signal.
     * Threshold: 60 (out of 100)
     */
    public boolean isStrongDivergence(DivergenceStrength strength) {
        return strength != null && strength.isDetected() && strength.getScore() >= 60;
    }

    /**
     * Check if divergence is weak.
     * Threshold: below 40 (out of 100)
     */
    public boolean isWeakDivergence(DivergenceStrength strength) {
        return strength != null && strength.isDetected() && strength.getScore() < 40;
    }

    /**
     * DivergenceStrength - Result of divergence analysis.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DivergenceStrength {

        /**
         * Is divergence detected?
         */
        @Builder.Default
        private boolean detected = false;

        /**
         * Overall divergence strength score (0-100)
         */
        @Builder.Default
        private double score = 0;

        /**
         * Base strength component
         */
        @Builder.Default
        private double baseStrength = 0;

        /**
         * Duration factor (1.0-1.5)
         */
        @Builder.Default
        private double durationFactor = 1.0;

        /**
         * Magnitude factor (1.0-1.5)
         */
        @Builder.Default
        private double magnitudeFactor = 1.0;

        /**
         * Velocity factor (0.7-1.2)
         */
        @Builder.Default
        private double velocityFactor = 1.0;

        /**
         * Confluence factor (0.5-1.5)
         */
        @Builder.Default
        private double confluenceFactor = 1.0;

        /**
         * Has PCR divergence?
         */
        @Builder.Default
        private boolean hasPcrDivergence = false;

        /**
         * Has OI divergence?
         */
        @Builder.Default
        private boolean hasOiDivergence = false;

        /**
         * Divergence type (PCR, OI, PCR+OI)
         */
        @Builder.Default
        private String type = "NONE";

        /**
         * Divergence direction (BULLISH, BEARISH, NEUTRAL)
         */
        @Builder.Default
        private String direction = "NEUTRAL";

        /**
         * Create empty (no divergence) result.
         */
        public static DivergenceStrength none() {
            return DivergenceStrength.builder()
                    .detected(false)
                    .score(0)
                    .type("NONE")
                    .direction("NEUTRAL")
                    .build();
        }

        /**
         * Is this a strong divergence (score >= 60)?
         */
        public boolean isStrong() {
            return detected && score >= 60;
        }

        /**
         * Is this a medium divergence (score 40-60)?
         */
        public boolean isMedium() {
            return detected && score >= 40 && score < 60;
        }

        /**
         * Is this a weak divergence (score < 40)?
         */
        public boolean isWeak() {
            return detected && score < 40;
        }

        /**
         * Get strength label.
         */
        public String getStrengthLabel() {
            if (!detected) return "NONE";
            if (score >= 80) return "VERY_STRONG";
            if (score >= 60) return "STRONG";
            if (score >= 40) return "MEDIUM";
            if (score >= 20) return "WEAK";
            return "VERY_WEAK";
        }
    }
}
