package com.kotsin.consumer.trading.state.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * MtfAnalysisDTO - Complete MTF (Multi-Timeframe) analysis for dashboard transparency.
 *
 * Shows all hierarchical analysis details:
 * - HTF/LTF bias alignment
 * - Swing range and zone position
 * - F&O flow alignment
 * - Entry sequence validation
 * - Signal quality tier
 *
 * This enables "no black box" visibility into why signals trigger.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MtfAnalysisDTO {

    // ========== HIERARCHICAL ANALYSIS ==========

    /** Higher timeframe used for context (e.g., "1h", "4h") */
    private String htfTimeframe;

    /** Lower timeframe used for entry (e.g., "5m", "15m") */
    private String ltfTimeframe;

    /** HTF market bias: BULLISH, BEARISH, or UNKNOWN */
    private String htfBias;

    /** LTF market bias: BULLISH, BEARISH, or UNKNOWN */
    private String ltfBias;

    /** Whether HTF and LTF bias are aligned */
    private boolean biasAligned;

    /** HTF structure description (e.g., "Higher highs, higher lows") */
    private String htfStructure;

    /** LTF structure description */
    private String ltfStructure;

    // ========== SWING RANGE ==========

    /** Current swing high price */
    private double swingHigh;

    /** Current swing low price */
    private double swingLow;

    /** Equilibrium price (50% of swing) */
    private double equilibrium;

    /** Current zone position: PREMIUM, DISCOUNT, EQUILIBRIUM, ABOVE_RANGE, BELOW_RANGE */
    private String zonePosition;

    /** Position within swing range as percentage (0% = swing low, 100% = swing high) */
    private double rangePositionPercent;

    /** Swing size as percentage */
    private double swingSizePercent;

    /** Whether current swing is an upswing (low before high) */
    private boolean isUpswing;

    // ========== F&O FLOW ALIGNMENT ==========

    /** Flow gate status: PASS, BLOCKED, NO_DATA */
    private String flowStatus;

    /** OI interpretation: LONG_BUILDUP, SHORT_COVERING, SHORT_BUILDUP, LONG_UNWINDING, NEUTRAL */
    private String flowInterpretation;

    /** Reason for flow gate decision */
    private String flowReason;

    /** Flow confidence percentage */
    private Double flowConfidence;

    // ========== ENTRY SEQUENCE ==========

    /** Number of entry sequence steps completed */
    private int completedSteps;

    /** Total number of entry sequence steps */
    private int totalSteps;

    /** Names of completed steps */
    private List<String> completedStepNames;

    /** Names of missing steps */
    private List<String> missingStepNames;

    /** Whether core requirements (HTF + Zone + Flow) are met */
    private boolean coreRequirementsMet;

    /** Whether full sequence is met (all 5 steps) */
    private boolean fullSequenceMet;

    // ========== QUALITY TIER ==========

    /** Quality tier: A_PLUS, A, B, C, REJECT */
    private String qualityTier;

    /** Quality tier display string: A+, A, B, C, REJECT */
    private String qualityTierDisplay;

    /** Quality summary explanation */
    private String qualitySummary;

    /** Detailed quality reasoning */
    private List<String> qualityReasons;

    /** Quality score (0-100 for backwards compatibility) */
    private int qualityScore;

    // ========== SMC DETAILS ==========

    /** Whether at HTF demand zone (for LONG) */
    private boolean atHtfDemand;

    /** Whether at HTF supply zone (for SHORT) */
    private boolean atHtfSupply;

    /** LTF liquidity sweep detected */
    private boolean ltfSweepDetected;

    /** LTF sweep side: BUY_SIDE or SELL_SIDE */
    private String ltfSweepSide;

    /** Recent CHoCH (Change of Character) detected */
    private boolean ltfChochDetected;

    /** CHoCH direction: BULLISH or BEARISH */
    private String ltfChochDirection;

    /** Recent BOS (Break of Structure) detected */
    private boolean ltfBosDetected;

    /** BOS direction: BULLISH or BEARISH */
    private String ltfBosDirection;

    /**
     * Get a summary string for logging.
     */
    public String toSummaryString() {
        return String.format(
                "HTF(%s)=%s LTF(%s)=%s | Zone=%s(%.0f%%) | Flow=%s | Seq=%d/%d | Tier=%s",
                htfTimeframe, htfBias,
                ltfTimeframe, ltfBias,
                zonePosition, rangePositionPercent,
                flowStatus,
                completedSteps, totalSteps,
                qualityTierDisplay
        );
    }
}
