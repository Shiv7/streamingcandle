package com.kotsin.consumer.trading.state.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * ActiveSetupInfo - Information about an active setup being watched.
 * An instrument can have multiple setups being tracked in parallel.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActiveSetupInfo {

    /** Strategy identifier (e.g., "FUDKII", "PIVOT_RETEST") */
    private String strategyId;

    /** Human-readable setup description (e.g., "BB squeeze + ST SHORT") */
    private String setupDescription;

    /** Trade direction: "LONG" or "SHORT" */
    private String direction;

    /** Key price level being watched */
    private double keyLevel;

    /** Timestamp when setup started being watched */
    private long watchingStartTime;

    /** Duration in ms since watching started */
    private long watchingDurationMs;

    /** List of entry conditions and their current status */
    private List<ConditionCheck> conditions;

    /** Overall progress percentage (0-100) based on conditions */
    private int progressPercent;

    /** Name of the condition blocking entry (null if none) */
    private String blockingCondition;
}
