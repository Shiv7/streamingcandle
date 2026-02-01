package com.kotsin.consumer.gate.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GateResult - Result of passing signal through a quality gate.
 *
 * Contains:
 * - Pass/Fail status
 * - Score contribution
 * - Detailed reasoning
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GateResult {

    private String gateName;
    private boolean passed;
    private double score;            // 0-100 contribution
    private double weight;           // Gate weight (0-1)
    private String reason;
    private Instant timestamp;

    @Builder.Default
    private Map<String, Object> details = new HashMap<>();

    public double getWeightedScore() {
        return passed ? score * weight : 0;
    }

    public static GateResult pass(String gateName, double score, double weight, String reason) {
        return GateResult.builder()
            .gateName(gateName)
            .passed(true)
            .score(score)
            .weight(weight)
            .reason(reason)
            .timestamp(Instant.now())
            .build();
    }

    public static GateResult fail(String gateName, double weight, String reason) {
        return GateResult.builder()
            .gateName(gateName)
            .passed(false)
            .score(0)
            .weight(weight)
            .reason(reason)
            .timestamp(Instant.now())
            .build();
    }

    /**
     * Aggregate result from multiple gates.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChainResult {
        private String symbol;
        private String signalType;
        private boolean passed;          // All required gates passed
        private double totalScore;       // Weighted score (0-100)
        private int gatesPassed;
        private int gatesFailed;
        private int totalGates;
        @Builder.Default
        private List<GateResult> gateResults = new ArrayList<>();
        private String failureReason;    // First failure reason
        private Instant timestamp;

        public void addResult(GateResult result) {
            gateResults.add(result);
            totalGates++;

            if (result.isPassed()) {
                gatesPassed++;
                totalScore += result.getWeightedScore();
            } else {
                gatesFailed++;
                if (failureReason == null) {
                    failureReason = result.getReason();
                }
            }
        }

        public double getPassRate() {
            return totalGates > 0 ? (double) gatesPassed / totalGates * 100 : 0;
        }

        public boolean isHighQuality() {
            return passed && totalScore >= 70;
        }

        public List<String> getFailedGates() {
            return gateResults.stream()
                .filter(r -> !r.isPassed())
                .map(GateResult::getGateName)
                .toList();
        }

        public List<String> getPassedGates() {
            return gateResults.stream()
                .filter(GateResult::isPassed)
                .map(GateResult::getGateName)
                .toList();
        }
    }
}
