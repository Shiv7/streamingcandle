package com.kotsin.consumer.trading.gate;

import com.kotsin.consumer.domain.model.FamilyCandle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * FlowAlignmentGate - Blocks signals when F&O flow contradicts the trade direction.
 *
 * THE PROBLEM:
 * - Old code would generate LONG signals even when OI interpretation showed SHORT_BUILDUP
 * - This led to signals like: "LONG @ 377 with R:R 3.0" while flow showed SHORT_BUILDUP
 * - Trading against institutional flow is a losing strategy
 *
 * THE FIX:
 * - This gate BLOCKS signals that contradict the flow
 * - LONG requires: LONG_BUILDUP, SHORT_COVERING, or NEUTRAL (not SHORT_BUILDUP, LONG_UNWINDING)
 * - SHORT requires: SHORT_BUILDUP, LONG_UNWINDING, or NEUTRAL (not LONG_BUILDUP, SHORT_COVERING)
 *
 * FLOW INTERPRETATION:
 * - LONG_BUILDUP:    Price up + OI up   = Fresh longs entering (bullish continuation)
 * - SHORT_COVERING:  Price up + OI down = Shorts exiting (bullish, but may exhaust)
 * - SHORT_BUILDUP:   Price down + OI up = Fresh shorts entering (bearish continuation)
 * - LONG_UNWINDING:  Price down + OI down = Longs exiting (bearish, but may exhaust)
 * - NEUTRAL:         No significant price/OI change
 */
@Slf4j
@Service
public class FlowAlignmentGate {

    /**
     * Evaluate if a signal direction is aligned with the F&O flow.
     *
     * @param direction   The intended trade direction (LONG or SHORT)
     * @param familyCandle The candle with OI interpretation
     * @return FlowGateResult indicating PASS or BLOCKED
     */
    public FlowGateResult evaluate(SignalDirection direction, FamilyCandle familyCandle) {
        if (familyCandle == null) {
            log.debug("[FLOW_GATE] No candle data - allowing signal");
            return FlowGateResult.pass("No candle data - allowing");
        }

        String oiInterpretation = familyCandle.getOiInterpretation();

        // No OI data - allow but note it
        if (oiInterpretation == null || oiInterpretation.isEmpty() ||
            "NO_OI_DATA".equals(oiInterpretation) || "NO_OI_CHANGE".equals(oiInterpretation)) {
            log.debug("[FLOW_GATE] {} No OI data available - allowing signal", familyCandle.getFamilyId());
            return FlowGateResult.passNoData();
        }

        FlowGateResult result = evaluateWithFlow(direction, oiInterpretation,
                familyCandle.getOiInterpretationConfidence());

        if (result.isBlocked()) {
            log.info("[FLOW_GATE] {} BLOCKED {} signal | flow={} | reason={}",
                    familyCandle.getFamilyId(), direction, oiInterpretation, result.reason());
        } else {
            log.debug("[FLOW_GATE] {} PASSED {} signal | flow={} | aligned={}",
                    familyCandle.getFamilyId(), direction, oiInterpretation, result.flowAligned());
        }

        return result;
    }

    /**
     * Evaluate with explicit OI interpretation string.
     *
     * @param direction        The intended trade direction
     * @param oiInterpretation The OI interpretation string
     * @param confidence       The confidence level (0-1)
     * @return FlowGateResult
     */
    public FlowGateResult evaluateWithFlow(SignalDirection direction, String oiInterpretation,
                                            Double confidence) {
        if (oiInterpretation == null || oiInterpretation.isEmpty() ||
            "NO_OI_DATA".equals(oiInterpretation) || "NO_OI_CHANGE".equals(oiInterpretation) ||
            "NEUTRAL".equals(oiInterpretation)) {
            return FlowGateResult.passNoData();
        }

        double conf = confidence != null ? confidence : 0.5;

        // LONG signal checks
        if (direction == SignalDirection.LONG) {
            // BLOCKING conditions for LONG
            if ("SHORT_BUILDUP".equals(oiInterpretation)) {
                return FlowGateResult.blocked(
                        "SHORT_BUILDUP flow contradicts LONG direction - " +
                        "institutional shorts entering (confidence: " + String.format("%.0f%%", conf * 100) + ")");
            }
            if ("LONG_UNWINDING".equals(oiInterpretation)) {
                return FlowGateResult.blocked(
                        "LONG_UNWINDING flow contradicts LONG direction - " +
                        "existing longs exiting (confidence: " + String.format("%.0f%%", conf * 100) + ")");
            }

            // PASSING conditions for LONG
            if ("LONG_BUILDUP".equals(oiInterpretation)) {
                return FlowGateResult.passAligned(
                        "LONG_BUILDUP confirms LONG direction - fresh longs entering");
            }
            if ("SHORT_COVERING".equals(oiInterpretation)) {
                return FlowGateResult.passAligned(
                        "SHORT_COVERING supports LONG direction - shorts exiting");
            }
        }

        // SHORT signal checks
        if (direction == SignalDirection.SHORT) {
            // BLOCKING conditions for SHORT
            if ("LONG_BUILDUP".equals(oiInterpretation)) {
                return FlowGateResult.blocked(
                        "LONG_BUILDUP flow contradicts SHORT direction - " +
                        "institutional longs entering (confidence: " + String.format("%.0f%%", conf * 100) + ")");
            }
            if ("SHORT_COVERING".equals(oiInterpretation)) {
                return FlowGateResult.blocked(
                        "SHORT_COVERING flow contradicts SHORT direction - " +
                        "existing shorts exiting (confidence: " + String.format("%.0f%%", conf * 100) + ")");
            }

            // PASSING conditions for SHORT
            if ("SHORT_BUILDUP".equals(oiInterpretation)) {
                return FlowGateResult.passAligned(
                        "SHORT_BUILDUP confirms SHORT direction - fresh shorts entering");
            }
            if ("LONG_UNWINDING".equals(oiInterpretation)) {
                return FlowGateResult.passAligned(
                        "LONG_UNWINDING supports SHORT direction - longs exiting");
            }
        }

        // Unknown interpretation - allow with warning
        return FlowGateResult.pass("Unknown OI interpretation: " + oiInterpretation);
    }

    // ============ MODELS ============

    /**
     * Signal direction for flow alignment check.
     */
    public enum SignalDirection {
        LONG,
        SHORT
    }

    /**
     * Result of flow alignment evaluation.
     */
    public record FlowGateResult(
            boolean passed,
            boolean hasFlowData,
            boolean flowAligned,
            String reason
    ) {
        public static FlowGateResult pass(String reason) {
            return new FlowGateResult(true, true, false, reason);
        }

        public static FlowGateResult passNoData() {
            return new FlowGateResult(true, false, false, "No F&O flow data available");
        }

        public static FlowGateResult passAligned(String reason) {
            return new FlowGateResult(true, true, true, reason);
        }

        public static FlowGateResult blocked(String reason) {
            return new FlowGateResult(false, true, false, reason);
        }

        public boolean isBlocked() {
            return !passed;
        }
    }
}
