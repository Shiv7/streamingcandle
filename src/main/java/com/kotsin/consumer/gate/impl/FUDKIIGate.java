package com.kotsin.consumer.gate.impl;

import com.kotsin.consumer.gate.SignalGate;
import com.kotsin.consumer.gate.model.GateResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * FUDKIIGate - Validates FUDKII score alignment for signals.
 *
 * FUDKII = Flow + Urgency + Direction + Kyle Lambda + Imbalance + Intensity
 *
 * Checks:
 * - FUDKII score threshold
 * - Score alignment with signal direction
 * - Component strength
 */
@Component
public class FUDKIIGate implements SignalGate {

    private static final double MIN_FUDKII_SCORE = 40;       // Minimum acceptable
    private static final double GOOD_FUDKII_SCORE = 60;      // Good quality
    private static final double EXCELLENT_FUDKII_SCORE = 80; // High quality

    @Override
    public String getName() {
        return "FUDKII";
    }

    @Override
    public double getWeight() {
        return 0.20;  // 20% weight - important institutional flow indicator
    }

    @Override
    public boolean isRequired() {
        return false;
    }

    @Override
    public GateResult evaluate(Map<String, Object> context) {
        String direction = (String) context.get("signalDirection");
        Double fudkiiScore = (Double) context.get("fudkiiScore");

        // Also check individual components if available
        Double flowScore = (Double) context.get("flowScore");
        Double urgencyScore = (Double) context.get("urgencyScore");
        Double directionScore = (Double) context.get("directionScore");
        Double kyleScore = (Double) context.get("kyleScore");
        Double imbalanceScore = (Double) context.get("imbalanceScore");
        Double intensityScore = (Double) context.get("intensityScore");

        if (fudkiiScore == null) {
            // Try to construct from components
            if (flowScore != null || urgencyScore != null || directionScore != null) {
                fudkiiScore = calculateFromComponents(
                    flowScore, urgencyScore, directionScore,
                    kyleScore, imbalanceScore, intensityScore);
            } else {
                return GateResult.fail(getName(), getWeight(), "FUDKII score not available");
            }
        }

        if (direction == null) {
            return GateResult.fail(getName(), getWeight(), "Signal direction not specified");
        }

        boolean isLong = "LONG".equalsIgnoreCase(direction);

        // FUDKII score interpretation:
        // Positive = bullish institutional flow
        // Negative = bearish institutional flow
        // The absolute value indicates strength

        double absScore = Math.abs(fudkiiScore);
        boolean scoreBullish = fudkiiScore > 0;

        // Check direction alignment
        boolean aligned = (isLong && scoreBullish) || (!isLong && !scoreBullish);

        if (!aligned && absScore > MIN_FUDKII_SCORE) {
            return GateResult.fail(getName(), getWeight(),
                String.format("FUDKII against signal: %.1f (signal: %s, FUDKII: %s)",
                    fudkiiScore, direction, scoreBullish ? "BULLISH" : "BEARISH"));
        }

        if (absScore < MIN_FUDKII_SCORE) {
            return GateResult.fail(getName(), getWeight(),
                String.format("FUDKII too weak: %.1f (need > %.1f)", absScore, MIN_FUDKII_SCORE));
        }

        double score;
        String reason;

        if (absScore >= EXCELLENT_FUDKII_SCORE) {
            score = 100;
            reason = String.format("Excellent FUDKII: %.1f - strong institutional flow", fudkiiScore);
        } else if (absScore >= GOOD_FUDKII_SCORE) {
            score = 80;
            reason = String.format("Good FUDKII: %.1f - clear institutional activity", fudkiiScore);
        } else {
            score = 60;
            reason = String.format("Acceptable FUDKII: %.1f - moderate institutional flow", fudkiiScore);
        }

        GateResult result = GateResult.pass(getName(), score, getWeight(), reason);
        result.getDetails().put("fudkiiScore", fudkiiScore);
        result.getDetails().put("aligned", aligned);

        // Add component breakdown if available
        if (flowScore != null) result.getDetails().put("flowScore", flowScore);
        if (urgencyScore != null) result.getDetails().put("urgencyScore", urgencyScore);
        if (directionScore != null) result.getDetails().put("directionScore", directionScore);
        if (kyleScore != null) result.getDetails().put("kyleScore", kyleScore);
        if (imbalanceScore != null) result.getDetails().put("imbalanceScore", imbalanceScore);
        if (intensityScore != null) result.getDetails().put("intensityScore", intensityScore);

        return result;
    }

    private double calculateFromComponents(Double flow, Double urgency, Double direction,
                                            Double kyle, Double imbalance, Double intensity) {
        double total = 0;
        int count = 0;

        if (flow != null) { total += flow; count++; }
        if (urgency != null) { total += urgency; count++; }
        if (direction != null) { total += direction; count++; }
        if (kyle != null) { total += kyle; count++; }
        if (imbalance != null) { total += imbalance; count++; }
        if (intensity != null) { total += intensity; count++; }

        return count > 0 ? total / count * 100 : 0;  // Normalize to 0-100
    }
}
