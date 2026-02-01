package com.kotsin.consumer.gate.impl;

import com.kotsin.consumer.gate.SignalGate;
import com.kotsin.consumer.gate.model.GateResult;
import com.kotsin.consumer.pattern.PatternAnalyzer.PatternResult;
import com.kotsin.consumer.pattern.PatternAnalyzer.DetectedPattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PatternGate - Validates candlestick and technical patterns.
 *
 * Checks:
 * 1. High-confidence pattern detected
 * 2. Pattern direction aligns with signal direction
 * 3. Pattern type quality (reversal vs continuation)
 * 4. Multiple pattern confluence
 */
@Component
@Slf4j
public class PatternGate implements SignalGate {

    private static final String LOG_PREFIX = "[PATTERN-GATE]";

    @Value("${gate.pattern.weight:0.15}")
    private double weight;

    @Value("${gate.pattern.min.confidence:0.6}")
    private double minConfidence;

    @Value("${gate.pattern.required:false}")
    private boolean required;

    @Override
    public String getName() {
        return "PATTERN";
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
            // Get pattern result from context
            PatternResult patternResult = (PatternResult) context.get("patternResult");
            String signalDirection = (String) context.get("signalDirection");

            if (patternResult == null || patternResult.getPatterns().isEmpty()) {
                return GateResult.fail(getName(), weight, "No patterns detected");
            }

            List<DetectedPattern> patterns = patternResult.getPatterns();

            // 1. Check for high-confidence patterns
            double maxConfidence = patterns.stream()
                .mapToDouble(DetectedPattern::getConfidence)
                .max().orElse(0);

            if (maxConfidence >= 0.8) {
                score += 40;
                reasons.add("High confidence pattern: " + String.format("%.0f%%", maxConfidence * 100));
            } else if (maxConfidence >= minConfidence) {
                score += 20;
                reasons.add("Moderate confidence pattern: " + String.format("%.0f%%", maxConfidence * 100));
            }

            // 2. Check direction alignment
            if (signalDirection != null) {
                long alignedCount = patterns.stream()
                    .filter(p -> signalDirection.equals(p.getDirection()))
                    .count();

                if (alignedCount > 0) {
                    score += 30;
                    reasons.add("Pattern aligned with signal: " + alignedCount + " patterns");
                } else {
                    // Check for conflicting patterns
                    long conflictCount = patterns.stream()
                        .filter(p -> !signalDirection.equals(p.getDirection()) &&
                                    !"NEUTRAL".equals(p.getDirection()))
                        .count();

                    if (conflictCount > 0) {
                        score -= 20;
                        reasons.add("Conflicting patterns: " + conflictCount);
                    }
                }
            }

            // 3. Check pattern quality
            boolean hasReversalPattern = patterns.stream()
                .anyMatch(p -> isReversalPattern(p.getPatternType().name()));

            boolean hasContinuationPattern = patterns.stream()
                .anyMatch(p -> isContinuationPattern(p.getPatternType().name()));

            if (hasReversalPattern) {
                score += 15;
                reasons.add("Reversal pattern detected");
            }
            if (hasContinuationPattern) {
                score += 10;
                reasons.add("Continuation pattern detected");
            }

            // 4. Multiple pattern confluence
            if (patterns.size() >= 2) {
                score += 15;
                reasons.add("Multiple patterns: " + patterns.size());
            }

            // Normalize score to 0-100
            score = Math.max(0, Math.min(100, score));

            boolean passed = score >= 30;
            String reason = String.join("; ", reasons);

            if (passed) {
                return GateResult.pass(getName(), score, weight, reason);
            } else {
                return GateResult.fail(getName(), weight, reason.isEmpty() ? "Score below threshold" : reason);
            }

        } catch (Exception e) {
            log.error("{} Error evaluating: {}", LOG_PREFIX, e.getMessage());
            return GateResult.fail(getName(), weight, "Evaluation error: " + e.getMessage());
        }
    }

    private boolean isReversalPattern(String patternType) {
        return patternType.contains("ENGULFING") ||
               patternType.contains("STAR") ||
               patternType.contains("HAMMER") ||
               patternType.contains("SHOOTING") ||
               patternType.contains("HARAMI") ||
               patternType.contains("PIERCING") ||
               patternType.contains("DARK_CLOUD");
    }

    private boolean isContinuationPattern(String patternType) {
        return patternType.contains("SOLDIERS") ||
               patternType.contains("CROWS") ||
               patternType.contains("MARUBOZU");
    }
}
