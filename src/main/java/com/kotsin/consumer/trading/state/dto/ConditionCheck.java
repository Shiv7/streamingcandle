package com.kotsin.consumer.trading.state.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ConditionCheck - Represents a single entry condition and its current status.
 * Used for visualizing how close an instrument is to triggering a signal.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConditionCheck {

    /** Name of the condition (e.g., "At Level", "OFI Aligned", "R:R >= 1.5") */
    private String conditionName;

    /** Whether this condition is currently passing */
    private boolean passed;

    /** Current value of the metric being checked */
    private double currentValue;

    /** Required value to pass the condition */
    private double requiredValue;

    /** Comparison operator: ">", "<", ">=", "<=", "within", "equals" */
    private String comparison;

    /** Progress percentage (0-100) - how close to passing */
    private int progressPercent;

    /** Human-readable display value (e.g., "0.28 (need 1.5)") */
    private String displayValue;
}
