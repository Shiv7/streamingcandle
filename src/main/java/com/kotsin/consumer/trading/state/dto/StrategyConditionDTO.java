package com.kotsin.consumer.trading.state.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * StrategyConditionDTO - Extended condition details for dashboard transparency.
 *
 * Shows CURRENT vs REQUIRED values for each strategy condition,
 * enabling users to see exactly why signals trigger or not.
 *
 * Categories:
 * - REQUIRED: Must be met for any valid signal
 * - OPTIMAL: Improves signal quality but not mandatory
 * - BONUS: Extra confirmation, nice to have
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyConditionDTO {

    /** Condition name (e.g., "HTF Bias", "Zone Location", "Flow Alignment") */
    private String name;

    /** Category: REQUIRED, OPTIMAL, or BONUS */
    private String category;

    /** Whether this condition is currently met */
    private boolean passed;

    /** Current value as string (e.g., "BULLISH", "DISCOUNT", "67%") */
    private String currentValue;

    /** Required value as string (e.g., "BULLISH or BEARISH", "DISCOUNT for LONG") */
    private String requiredValue;

    /** Explanation of what this condition checks */
    private String explanation;

    /** Progress percentage (0-100) - how close to passing */
    private int progressPercent;

    /** Source/component: HTF, LTF, FLOW, ZONE, TECHNICAL */
    private String source;

    /** Timeframe this condition applies to (e.g., "1h", "5m") */
    private String timeframe;

    /** Additional details or notes */
    private String notes;

    /**
     * Create a PASSED condition.
     */
    public static StrategyConditionDTO passed(String name, String category, String currentValue,
                                               String requiredValue, String explanation,
                                               String source, String timeframe) {
        return StrategyConditionDTO.builder()
                .name(name)
                .category(category)
                .passed(true)
                .currentValue(currentValue)
                .requiredValue(requiredValue)
                .explanation(explanation)
                .progressPercent(100)
                .source(source)
                .timeframe(timeframe)
                .build();
    }

    /**
     * Create a FAILED condition with progress.
     */
    public static StrategyConditionDTO failed(String name, String category, String currentValue,
                                               String requiredValue, String explanation,
                                               int progressPercent, String source, String timeframe) {
        return StrategyConditionDTO.builder()
                .name(name)
                .category(category)
                .passed(false)
                .currentValue(currentValue)
                .requiredValue(requiredValue)
                .explanation(explanation)
                .progressPercent(progressPercent)
                .source(source)
                .timeframe(timeframe)
                .build();
    }
}
