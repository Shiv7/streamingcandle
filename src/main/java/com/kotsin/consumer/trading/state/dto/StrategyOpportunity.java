package com.kotsin.consumer.trading.state.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * StrategyOpportunity - Represents an instrument close to triggering a signal.
 * Used for the "near opportunities" feed in the dashboard.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyOpportunity {

    /** Scrip/instrument code */
    private String scripCode;

    /** Company/instrument name */
    private String companyName;

    /** Strategy identifier */
    private String strategyId;

    /** Trade direction: "LONG" or "SHORT" */
    private String direction;

    /** Opportunity score (0-100) - how close to triggering */
    private double opportunityScore;

    /** Current conditions status */
    private List<ConditionCheck> conditions;

    /** The next condition needed to trigger */
    private String nextConditionNeeded;

    /** Estimated timeframe (e.g., "OFI trending, may flip in 5-10 candles") */
    private String estimatedTimeframe;

    /** Current price */
    private double currentPrice;

    /** Key level being watched */
    private double keyLevel;
}
