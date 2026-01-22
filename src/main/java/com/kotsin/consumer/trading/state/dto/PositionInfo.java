package com.kotsin.consumer.trading.state.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PositionInfo - Simplified position information for dashboard display.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PositionInfo {

    /** Strategy that triggered this position */
    private String strategyId;

    /** Trade direction: "LONG" or "SHORT" */
    private String direction;

    /** Entry price */
    private double entryPrice;

    /** Current price */
    private double currentPrice;

    /** Stop loss price */
    private double stopLoss;

    /** Target 1 price */
    private double target1;

    /** Target 2 price */
    private double target2;

    /** Unrealized P&L */
    private double unrealizedPnL;

    /** P&L percentage */
    private double pnlPercent;

    /** Position quantity */
    private int quantity;

    /** Entry timestamp */
    private long entryTime;

    /** Position duration in ms */
    private long durationMs;

    /** Whether T1 has been hit */
    private boolean target1Hit;
}
