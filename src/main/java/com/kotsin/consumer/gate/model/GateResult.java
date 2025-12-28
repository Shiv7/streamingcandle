package com.kotsin.consumer.gate.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GateResult - Result of a gate evaluation
 * 
 * Encapsulates:
 * - Pass/Fail status
 * - Reason for the result
 * - Optional position multiplier (for scaling based on conviction)
 * - Gate name for logging
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GateResult {

    private boolean passed;
    private String gateName;
    private String reason;
    private String detail;
    private double positionMultiplier;  // 1.0 = normal, 0.7 = reduce, 1.2 = boost

    // ========== Static Factory Methods ==========

    /**
     * Gate passed with default multiplier
     */
    public static GateResult pass() {
        return GateResult.builder()
                .passed(true)
                .positionMultiplier(1.0)
                .build();
    }

    /**
     * Gate passed with reason
     */
    public static GateResult pass(String reason) {
        return GateResult.builder()
                .passed(true)
                .reason(reason)
                .positionMultiplier(1.0)
                .build();
    }

    /**
     * Gate passed with position size adjustment
     */
    public static GateResult pass(String reason, double positionMultiplier) {
        return GateResult.builder()
                .passed(true)
                .reason(reason)
                .positionMultiplier(positionMultiplier)
                .build();
    }

    /**
     * Gate failed with reason
     */
    public static GateResult fail(String reason) {
        return GateResult.builder()
                .passed(false)
                .reason(reason)
                .positionMultiplier(0.0)
                .build();
    }

    /**
     * Gate failed with reason and detail
     */
    public static GateResult fail(String reason, String detail) {
        return GateResult.builder()
                .passed(false)
                .reason(reason)
                .detail(detail)
                .positionMultiplier(0.0)
                .build();
    }

    // ========== Convenience Methods ==========

    /**
     * Get formatted log message
     */
    public String toLogString() {
        StringBuilder sb = new StringBuilder();
        if (passed) {
            sb.append("✅ PASSED");
        } else {
            sb.append("🚫 FAILED");
        }
        if (gateName != null) {
            sb.append(" | gate=").append(gateName);
        }
        if (reason != null) {
            sb.append(" | reason=").append(reason);
        }
        if (detail != null) {
            sb.append(" | detail=").append(detail);
        }
        if (passed && positionMultiplier != 1.0) {
            sb.append(" | multiplier=").append(String.format("%.2f", positionMultiplier));
        }
        return sb.toString();
    }

    /**
     * Combine two gate results (AND logic)
     */
    public GateResult and(GateResult other) {
        if (!this.passed) {
            return this;
        }
        if (!other.passed) {
            return other;
        }
        // Both passed - combine multipliers
        return GateResult.builder()
                .passed(true)
                .reason(this.reason + " & " + other.reason)
                .positionMultiplier(this.positionMultiplier * other.positionMultiplier)
                .build();
    }
}

