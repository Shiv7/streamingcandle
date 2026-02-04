package com.kotsin.consumer.gate.impl;

import com.kotsin.consumer.gate.SignalGate;
import com.kotsin.consumer.gate.model.GateResult;
import com.kotsin.consumer.signal.model.FudkiiScore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * QuantScoreGate - Validates quantitative signal quality.
 *
 * Comprehensive validation of:
 * 1. FUDKII composite score
 * 2. Component alignment (all 6 FUDKII components)
 * 3. Confidence level
 * 4. Signal consistency
 */
@Component
@Slf4j
public class QuantScoreGate implements SignalGate {

    private static final String LOG_PREFIX = "[QUANT-GATE]";

    @Value("${gate.quant.weight:0.20}")
    private double weight;

    @Value("${gate.quant.min.score:40}")
    private double minScore;

    @Value("${gate.quant.min.confidence:0.5}")
    private double minConfidence;

    @Value("${gate.quant.min.alignment:3}")
    private int minAlignedComponents;

    @Value("${gate.quant.required:true}")
    private boolean required;

    @Override
    public String getName() {
        return "QUANT_SCORE";
    }

    @Override
    public double getWeight() {
        return weight;
    }

    @Override
    public boolean isRequired() {
        return required;
    }

    @Override
    public GateResult evaluate(Map<String, Object> context) {
        List<String> reasons = new ArrayList<>();
        double score = 0;

        try {
            FudkiiScore fudkiiScore = (FudkiiScore) context.get("fudkiiScore");

            if (fudkiiScore == null) {
                return GateResult.fail(getName(), weight, "No FUDKII score available");
            }

            double compositeScore = fudkiiScore.getCompositeScore();
            double confidence = fudkiiScore.getConfidence();
            String direction = fudkiiScore.getDirection() != null ?
                fudkiiScore.getDirection().name() : "NEUTRAL";

            // 1. Check composite score (0-40 points)
            if (compositeScore >= 70) {
                score += 40;
                reasons.add("Excellent score: " + String.format("%.1f", compositeScore));
            } else if (compositeScore >= 60) {
                score += 30;
                reasons.add("Strong score: " + String.format("%.1f", compositeScore));
            } else if (compositeScore >= minScore) {
                score += 20;
                reasons.add("Moderate score: " + String.format("%.1f", compositeScore));
            } else {
                reasons.add("Score below threshold: " + String.format("%.1f", compositeScore));
            }

            // 2. Check confidence (0-25 points)
            if (confidence >= 0.8) {
                score += 25;
                reasons.add("High confidence: " + String.format("%.0f%%", confidence * 100));
            } else if (confidence >= 0.6) {
                score += 15;
                reasons.add("Moderate confidence: " + String.format("%.0f%%", confidence * 100));
            } else if (confidence >= minConfidence) {
                score += 10;
                reasons.add("Low confidence: " + String.format("%.0f%%", confidence * 100));
            } else {
                reasons.add("Confidence below threshold: " + String.format("%.0f%%", confidence * 100));
            }

            // 3. Check component alignment (0-25 points)
            int alignedCount = countAlignedComponents(fudkiiScore, direction);
            if (alignedCount >= 5) {
                score += 25;
                reasons.add("Strong alignment: " + alignedCount + "/6 components");
            } else if (alignedCount >= 4) {
                score += 20;
                reasons.add("Good alignment: " + alignedCount + "/6 components");
            } else if (alignedCount >= minAlignedComponents) {
                score += 10;
                reasons.add("Moderate alignment: " + alignedCount + "/6 components");
            } else {
                reasons.add("Weak alignment: " + alignedCount + "/6 components");
            }

            // 4. Check for triggers (0-10 points)
            if (fudkiiScore.isActiveTrigger()) {
                score += 10;
                reasons.add("Active trigger confirmed");
            } else if (fudkiiScore.isWatchSetup()) {
                score += 5;
                reasons.add("Watch setup detected");
            }

            // Normalize score to 0-100
            score = Math.max(0, Math.min(100, score));

            // Pass if score meets threshold and required checks pass
            boolean passed = compositeScore >= minScore &&
                           confidence >= minConfidence &&
                           alignedCount >= minAlignedComponents;

            String reason = String.join("; ", reasons);
            if (passed) {
                return GateResult.pass(getName(), score, weight, reason);
            } else {
                return GateResult.fail(getName(), weight, reason);
            }

        } catch (Exception e) {
            log.error("{} Error evaluating: {}", LOG_PREFIX, e.getMessage());
            return GateResult.fail(getName(), weight, "Evaluation error: " + e.getMessage());
        }
    }

    /**
     * Count how many FUDKII components align with the signal direction.
     * For NEUTRAL direction, components near zero are considered aligned.
     */
    private int countAlignedComponents(FudkiiScore score, String direction) {
        boolean bullish = "BULLISH".equals(direction);
        boolean bearish = "BEARISH".equals(direction);
        boolean neutral = !bullish && !bearish;
        int aligned = 0;

        // Threshold for considering a score as "neutral"
        double neutralThreshold = 0.1;

        // Flow (OFI) - positive = bullish, negative = bearish, near-zero = neutral
        double flowScore = score.getFlowScore();
        if (neutral) {
            if (Math.abs(flowScore) < neutralThreshold) aligned++;
        } else if ((flowScore > 0) == bullish) {
            aligned++;
        }

        // Urgency bias - follows direction
        double urgencyBias = score.getUrgencyBias();
        if (neutral) {
            if (Math.abs(urgencyBias) < neutralThreshold) aligned++;
        } else if ((urgencyBias > 0) == bullish) {
            aligned++;
        }

        // Direction - follows bias
        double directionScore = score.getDirectionScore();
        if (neutral) {
            if (Math.abs(directionScore) < neutralThreshold) aligned++;
        } else if ((directionScore > 0) == bullish) {
            aligned++;
        }

        // Imbalance - buy/sell pressure
        double imbalanceScore = score.getImbalanceScore();
        if (neutral) {
            if (Math.abs(imbalanceScore) < neutralThreshold) aligned++;
        } else if ((imbalanceScore > 0) == bullish) {
            aligned++;
        }

        // Kyle score - check microprice deviation
        double microDev = score.getMicropriceDeviation();
        if (neutral) {
            if (Math.abs(microDev) < neutralThreshold) aligned++;
        } else if (microDev != 0) {
            if ((microDev > 0) == bullish) aligned++;
        } else {
            aligned++; // Zero deviation, count as aligned for directional signals
        }

        // Intensity (OI) - check interpretation
        FudkiiScore.OIInterpretation interp = score.getOiInterpretation();
        if (interp != null) {
            boolean oiBullish = interp == FudkiiScore.OIInterpretation.LONG_BUILDUP ||
                               interp == FudkiiScore.OIInterpretation.SHORT_COVERING;
            boolean oiBearish = interp == FudkiiScore.OIInterpretation.SHORT_BUILDUP ||
                               interp == FudkiiScore.OIInterpretation.LONG_UNWINDING;
            if (neutral) {
                if (interp == FudkiiScore.OIInterpretation.NEUTRAL) aligned++;
            } else if (oiBullish == bullish) {
                aligned++;
            }
        } else if (neutral) {
            aligned++; // No OI interpretation = neutral, aligned with neutral direction
        }

        return aligned;
    }
}
