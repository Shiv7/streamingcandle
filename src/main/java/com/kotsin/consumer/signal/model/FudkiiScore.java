package com.kotsin.consumer.signal.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * FudkiiScore - Composite FUDKII signal score breakdown.
 *
 * FUDKII = Flow + Urgency + Direction + Kyle + Imbalance + Intensity
 *
 * Each component is normalized to 0-1 range, with directional bias (-1 to +1).
 * Final score combines all components with configurable weights.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FudkiiScore {

    // ==================== IDENTITY ====================
    private String symbol;
    private String scripCode;
    private String timeframe;
    private Instant timestamp;

    // ==================== F - FLOW (Order Flow) ====================
    /**
     * OFI (Order Flow Imbalance) normalized score.
     * Derived from orderbook bid/ask changes.
     * Range: -1 (strong selling) to +1 (strong buying)
     */
    private double flowScore;

    /**
     * OFI momentum (rate of change).
     */
    private double flowMomentum;

    /**
     * Raw OFI value for context.
     */
    private double rawOfi;

    // ==================== U - URGENCY (IPU) ====================
    /**
     * IPU (Institutional Participation & Urgency) score.
     * Range: 0 to 1
     */
    private double urgencyScore;

    /**
     * Urgency direction bias.
     * Range: -1 (bearish urgency) to +1 (bullish urgency)
     */
    private double urgencyBias;

    /**
     * Exhaustion indicator.
     * High exhaustion suggests potential reversal.
     */
    private double exhaustion;

    /**
     * Momentum state.
     */
    private MomentumState momentumState;

    // ==================== D - DIRECTION (VCP + Structure) ====================
    /**
     * Directional bias from VCP runway analysis.
     * Range: -1 (bearish) to +1 (bullish)
     */
    private double directionScore;

    /**
     * Bullish runway strength (support below).
     */
    private double bullishRunway;

    /**
     * Bearish runway strength (resistance above).
     */
    private double bearishRunway;

    /**
     * Market structure.
     */
    private MarketStructure structure;

    // ==================== K - KYLE (Price Impact) ====================
    /**
     * Kyle's Lambda - price impact per unit volume.
     * Higher = less liquid, more volatile.
     */
    private double kyleLambda;

    /**
     * Kyle score normalized.
     * High score = favorable liquidity conditions.
     */
    private double kyleScore;

    /**
     * Microprice deviation from mid.
     */
    private double micropriceDeviation;

    // ==================== I - IMBALANCE (Volume) ====================
    /**
     * Volume imbalance score.
     * Range: -1 (sell imbalance) to +1 (buy imbalance)
     */
    private double imbalanceScore;

    /**
     * VIB (Volume Imbalance Bar) triggered.
     */
    private boolean vibTriggered;

    /**
     * DIB (Dollar Imbalance Bar) triggered.
     */
    private boolean dibTriggered;

    /**
     * TRB (Tick Run Bar) triggered.
     */
    private boolean trbTriggered;

    /**
     * Buy pressure ratio.
     */
    private double buyPressure;

    /**
     * Sell pressure ratio.
     */
    private double sellPressure;

    // ==================== I - INTENSITY (OI + Buildup) ====================
    /**
     * OI intensity score.
     * Combines OI change with price action.
     */
    private double intensityScore;

    /**
     * OI interpretation.
     */
    private OIInterpretation oiInterpretation;

    /**
     * OI change percentage.
     */
    private double oiChangePercent;

    /**
     * Suggests reversal based on OI + price divergence.
     */
    private boolean suggestsReversal;

    // ==================== COMPOSITE SCORES ====================
    /**
     * Final FUDKII composite score.
     * Range: 0 to 100
     */
    private double compositeScore;

    /**
     * Directional bias of composite score.
     * Range: -1 (bearish) to +1 (bullish)
     */
    private double compositeBias;

    /**
     * Signal strength classification.
     */
    private SignalStrength strength;

    /**
     * Recommended direction.
     */
    private Direction direction;

    /**
     * Confidence in the signal.
     * Range: 0 to 1
     */
    private double confidence;

    // ==================== TRIGGER CONDITIONS ====================
    /**
     * Is this a valid WATCH setup?
     */
    private boolean isWatchSetup;

    /**
     * Is this an ACTIVE trigger?
     */
    private boolean isActiveTrigger;

    /**
     * Reason for current state.
     */
    private String reason;

    // ==================== ENUMS ====================

    public enum MomentumState {
        ACCELERATING,      // Momentum increasing
        DECELERATING,      // Momentum decreasing
        STEADY,            // Stable momentum
        EXHAUSTED,         // Momentum exhausted
        REVERSING          // Momentum reversing
    }

    public enum MarketStructure {
        UPTREND,           // Higher highs, higher lows
        DOWNTREND,         // Lower highs, lower lows
        RANGE,             // Bounded range
        CONSOLIDATION,     // Tightening range
        BREAKOUT,          // Breaking out of range
        BREAKDOWN          // Breaking down from range
    }

    public enum OIInterpretation {
        LONG_BUILDUP,      // Price up + OI up
        SHORT_BUILDUP,     // Price down + OI up
        LONG_UNWINDING,    // Price down + OI down
        SHORT_COVERING,    // Price up + OI down
        NEUTRAL            // No clear interpretation
    }

    public enum SignalStrength {
        WEAK(0, 30),
        MODERATE(30, 50),
        STRONG(50, 70),
        VERY_STRONG(70, 85),
        EXTREME(85, 100);

        private final int minScore;
        private final int maxScore;

        SignalStrength(int min, int max) {
            this.minScore = min;
            this.maxScore = max;
        }

        public static SignalStrength fromScore(double score) {
            for (SignalStrength s : values()) {
                if (score >= s.minScore && score < s.maxScore) {
                    return s;
                }
            }
            return score >= 100 ? EXTREME : WEAK;
        }
    }

    public enum Direction {
        BULLISH,
        BEARISH,
        NEUTRAL
    }

    // ==================== HELPER METHODS ====================

    /**
     * Calculate composite score from components.
     * Weights are configurable.
     */
    public static double calculateComposite(
            double flow, double urgency, double direction,
            double kyle, double imbalance, double intensity,
            FudkiiWeights weights) {

        double weighted =
            Math.abs(flow) * weights.flowWeight +
            urgency * weights.urgencyWeight +
            Math.abs(direction) * weights.directionWeight +
            kyle * weights.kyleWeight +
            Math.abs(imbalance) * weights.imbalanceWeight +
            intensity * weights.intensityWeight;

        // Normalize to 0-100
        double totalWeight = weights.getTotalWeight();
        return (weighted / totalWeight) * 100;
    }

    /**
     * Calculate directional bias from components.
     */
    public static double calculateBias(
            double flow, double urgency, double urgencyBias,
            double direction, double imbalance) {

        // Weighted average of directional components
        double bias = (flow * 0.25) +
                      (urgencyBias * urgency * 0.25) +
                      (direction * 0.30) +
                      (imbalance * 0.20);

        return Math.max(-1, Math.min(1, bias));
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FudkiiWeights {
        @Builder.Default private double flowWeight = 0.15;
        @Builder.Default private double urgencyWeight = 0.20;
        @Builder.Default private double directionWeight = 0.25;
        @Builder.Default private double kyleWeight = 0.10;
        @Builder.Default private double imbalanceWeight = 0.15;
        @Builder.Default private double intensityWeight = 0.15;

        public double getTotalWeight() {
            return flowWeight + urgencyWeight + directionWeight +
                   kyleWeight + imbalanceWeight + intensityWeight;
        }
    }
}
