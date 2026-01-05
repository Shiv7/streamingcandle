package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * EvolutionMetrics - Comprehensive temporal evolution tracking for MTF analysis.
 *
 * Captures how key metrics evolved within a higher timeframe window
 * (e.g., 30 1-minute candles within a 30-minute window).
 *
 * CRITICAL FOR QUANT ANALYSIS:
 * - Preserves temporal sequence that OHLC aggregation loses
 * - Enables pattern detection (V-shape, reversal, trend)
 * - Tracks PCR, OI, and volume profile evolution
 * - Detects Wyckoff market phases
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvolutionMetrics {

    // ========================================================================
    // CANDLE SEQUENCE - Preserves temporal order lost in OHLC aggregation
    // ========================================================================
    private CandleSequence candleSequence;

    // ========================================================================
    // PCR EVOLUTION - Put/Call Ratio dynamics within window
    // ========================================================================
    private PCREvolution pcrEvolution;

    // ========================================================================
    // OI EVOLUTION - Open Interest accumulation timeline
    // ========================================================================
    private OIEvolution oiEvolution;

    // ========================================================================
    // VOLUME PROFILE EVOLUTION - POC and Value Area dynamics
    // ========================================================================
    private VolumeProfileEvolution volumeProfileEvolution;

    // ========================================================================
    // WYCKOFF PHASE - Market phase detection
    // ========================================================================
    private WyckoffPhase wyckoffPhase;

    // ========================================================================
    // INNER CLASSES
    // ========================================================================

    /**
     * CandleSequence - Preserves the temporal pattern of sub-candles.
     *
     * CRITICAL: Same OHLC can come from different sequences:
     * - "↑↑↑↓↓" = bullish start → reversal (SELL signal)
     * - "↓↓↑↑↑" = bearish start → recovery (BUY signal)
     * - "↑↓↑↓↑" = choppy/indecision (NO TRADE)
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CandleSequence {
        /** Visual pattern string: "↑↑↑↓↓", "↓↓↑↑↑", etc. */
        private String pattern;

        /** Direction array: [1, 1, 1, -1, -1] for programmatic analysis */
        private int[] directionArray;

        /** Longest consecutive same direction run */
        private int longestRun;

        /** Number of direction changes (reversals) */
        private int reversalCount;

        /** Index where first reversal occurred (0-based) */
        private int reversalIndex;

        /** Sequence classification */
        private SequenceType sequenceType;

        /** Per-candle momentum values: (close-open)/(high-low) */
        private double[] momentumArray;

        /** Linear regression slope of momentum */
        private double momentumSlope;

        /** R-squared of momentum trend (0-1, higher = stronger trend) */
        private double momentumR2;

        public enum SequenceType {
            TREND,          // Consistent direction throughout
            REVERSAL,       // Clear direction change
            CHOP,           // Multiple direction changes (>2)
            V_PATTERN,      // Down then up
            INVERTED_V,     // Up then down
            CONSOLIDATION   // Minimal movement
        }
    }

    /**
     * PCREvolution - Put/Call Ratio evolution within the window.
     *
     * Tracks how sentiment changed over the window period,
     * enabling detection of smart money positioning shifts.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PCREvolution {
        // Raw data arrays
        /** PCR at each sub-candle */
        private double[] pcrHistory;
        /** Call OI at each sub-candle */
        private long[] callOIHistory;
        /** Put OI at each sub-candle */
        private long[] putOIHistory;

        // Derived metrics
        /** PCR at window start */
        private double pcrStart;
        /** PCR at window end */
        private double pcrEnd;
        /** Net PCR change */
        private double pcrChange;
        /** Linear regression slope of PCR */
        private double pcrSlope;
        /** Standard deviation of PCR (volatility) */
        private double pcrVolatility;
        /** Minimum PCR in window */
        private double pcrMin;
        /** Maximum PCR in window */
        private double pcrMax;
        /** Index where PCR was minimum */
        private int pcrMinIndex;
        /** Index where PCR was maximum */
        private int pcrMaxIndex;

        // Pattern detection
        /** PCR pattern classification */
        private PCRPattern pcrPattern;
        /** True if PCR moving opposite to price */
        private boolean pcrDivergence;
        /** Divergence strength (0-1) */
        private double divergenceStrength;

        public enum PCRPattern {
            RISING,         // Steadily increasing (more puts, bearish sentiment)
            FALLING,        // Steadily decreasing (more calls, bullish sentiment)
            V_SHAPE,        // Down then up (panic → recovery)
            INVERTED_V,     // Up then down (complacency → fear)
            STABLE,         // Minimal change
            VOLATILE        // High variance, no clear trend
        }
    }

    /**
     * OIEvolution - Open Interest accumulation timeline.
     *
     * Tracks when OI accumulated (early/middle/late in window)
     * to identify smart money timing patterns.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OIEvolution {
        // Per-candle deltas
        /** Call OI change at each sub-candle */
        private long[] callOIDeltas;
        /** Put OI change at each sub-candle */
        private long[] putOIDeltas;
        /** Future OI change at each sub-candle */
        private long[] futureOIDeltas;

        // Accumulation phase detection
        /** When call OI accumulated: EARLY, MIDDLE, LATE, THROUGHOUT */
        private AccumulationPhase callAccumPhase;
        /** When put OI accumulated */
        private AccumulationPhase putAccumPhase;
        /** When future OI accumulated */
        private AccumulationPhase futAccumPhase;

        // Smart money metrics
        /** % of total OI change in first 40% of window */
        private double earlyOIRatio;
        /** % of total OI change in last 40% of window */
        private double lateOIRatio;
        /** True if OI moving opposite to price */
        private boolean oiDivergence;
        /** Rate of OI change (dOI/dt) */
        private double oiMomentum;

        // Buildup classification
        /** Overall buildup type */
        private BuildupType buildupType;
        /** Confidence in buildup classification (0-1) */
        private double buildupConfidence;

        public enum AccumulationPhase {
            EARLY,          // 0-40% of window
            MIDDLE,         // 40-60% of window
            LATE,           // 60-100% of window
            THROUGHOUT,     // Spread across entire window
            NONE            // No significant accumulation
        }

        public enum BuildupType {
            LONG_BUILDUP,       // Price up + OI up = new longs
            SHORT_BUILDUP,      // Price down + OI up = new shorts
            LONG_UNWINDING,     // Price down + OI down = longs exiting
            SHORT_COVERING,     // Price up + OI down = shorts exiting
            MIXED,              // Unclear pattern
            NEUTRAL             // Minimal OI change
        }
    }

    /**
     * VolumeProfileEvolution - Point of Control and Value Area dynamics.
     *
     * Tracks where volume concentrated over the window period,
     * showing institutional accumulation/distribution zones.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VolumeProfileEvolution {
        // Aggregated volume at price
        /** Combined VAP across all sub-candles */
        private Map<Double, Long> aggregatedVAP;

        // POC (Point of Control) migration
        /** POC at each sub-candle */
        private double[] pocHistory;
        /** Starting POC */
        private double pocStart;
        /** Ending POC */
        private double pocEnd;
        /** Net POC movement (+ = up, - = down) */
        private double pocMigration;
        /** POC trend direction */
        private POCTrend pocTrend;

        // Value Area dynamics
        /** VAH at each sub-candle */
        private double[] vahHistory;
        /** VAL at each sub-candle */
        private double[] valHistory;
        /** True if Value Area expanding (widening) */
        private boolean valueAreaExpanding;
        /** True if Value Area contracting (narrowing) */
        private boolean valueAreaContracting;
        /** Value Area shift direction */
        private ValueAreaShift valueAreaShift;

        // Final aggregated values
        /** Final aggregated POC */
        private double finalPOC;
        /** Final aggregated VAH */
        private double finalVAH;
        /** Final aggregated VAL */
        private double finalVAL;

        public enum POCTrend {
            RISING,     // POC moving up (bullish accumulation)
            FALLING,    // POC moving down (bearish accumulation)
            STABLE      // POC relatively stable
        }

        public enum ValueAreaShift {
            UPWARD,     // Value Area shifting higher
            DOWNWARD,   // Value Area shifting lower
            EXPANDING,  // Value Area widening (uncertainty)
            CONTRACTING,// Value Area narrowing (conviction)
            STABLE      // Minimal change
        }
    }

    /**
     * WyckoffPhase - Market phase detection based on Wyckoff methodology.
     *
     * Identifies accumulation, markup, distribution, and markdown phases
     * using price action, volume, and OI patterns.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WyckoffPhase {
        /** Current detected phase */
        private Phase phase;

        /** Index where this phase started */
        private int phaseStartIndex;

        /** Confidence in phase detection (0-1) */
        private double phaseStrength;

        /** True if phase appears to be transitioning */
        private boolean phaseTransition;

        /** Predicted next phase if transitioning */
        private Phase nextPhase;

        // Supporting evidence scores (0-1 each)
        /** How well volume pattern fits the phase */
        private double volumePatternFit;
        /** How well price action fits the phase */
        private double priceActionFit;
        /** How well OI pattern fits the phase */
        private double oiPatternFit;

        public enum Phase {
            ACCUMULATION,   // Smart money buying, price ranging low
            MARKUP,         // Uptrend after accumulation
            DISTRIBUTION,   // Smart money selling, price ranging high
            MARKDOWN,       // Downtrend after distribution
            UNKNOWN         // Insufficient data for classification
        }

        /**
         * Get overall fit score (average of all pattern fits)
         */
        public double getOverallFit() {
            return (volumePatternFit + priceActionFit + oiPatternFit) / 3.0;
        }
    }

    // ========================================================================
    // FACTORY METHODS
    // ========================================================================

    /**
     * Create empty evolution metrics
     */
    public static EvolutionMetrics empty() {
        return EvolutionMetrics.builder().build();
    }

    /**
     * Check if any evolution data is present
     */
    public boolean hasData() {
        return candleSequence != null ||
               pcrEvolution != null ||
               oiEvolution != null ||
               volumeProfileEvolution != null ||
               wyckoffPhase != null;
    }

    /**
     * Get overall confidence score (average of available component confidences)
     */
    public double getOverallConfidence() {
        double total = 0.0;
        int count = 0;

        if (candleSequence != null && candleSequence.getMomentumR2() > 0) {
            total += candleSequence.getMomentumR2();
            count++;
        }
        if (oiEvolution != null && oiEvolution.getBuildupConfidence() > 0) {
            total += oiEvolution.getBuildupConfidence();
            count++;
        }
        if (wyckoffPhase != null && wyckoffPhase.getPhaseStrength() > 0) {
            total += wyckoffPhase.getPhaseStrength();
            count++;
        }

        return count > 0 ? total / count : 0.0;
    }
}
