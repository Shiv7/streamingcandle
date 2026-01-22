package com.kotsin.consumer.trading.state.dto;

import com.kotsin.consumer.trading.state.InstrumentState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * InstrumentStateSnapshot - Complete snapshot of an instrument's current state.
 * Published to Kafka for dashboard consumption.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstrumentStateSnapshot {

    /** Scrip/instrument code */
    private String scripCode;

    /** Company/instrument name */
    private String companyName;

    /** Current state: IDLE, WATCHING, READY, POSITIONED, COOLDOWN */
    private InstrumentState state;

    /** Timestamp of this snapshot */
    private long stateTimestamp;

    /** When the instrument entered the current state */
    private long stateEntryTime;

    /** Duration in current state (ms) */
    private long stateDurationMs;

    // ============ Current Market Data ============

    /** Current price */
    private double currentPrice;

    /** Order Flow Imbalance Z-score */
    private double ofiZscore;

    /** Average True Range */
    private double atr;

    /** Volume-Weighted Price Indicator */
    private double vpin;

    /** SuperTrend direction (true = bullish) */
    private boolean superTrendBullish;

    /** SuperTrend flip detected this candle */
    private boolean superTrendFlip;

    /** Bollinger Band %B value */
    private double bbPercentB;

    /** Is BB squeezing */
    private boolean bbSqueezing;

    // ============ Active Setups (when WATCHING) ============

    /** List of setups being watched (can be multiple strategies) */
    private List<ActiveSetupInfo> activeSetups;

    // ============ Position Info (when POSITIONED) ============

    /** Position details when in POSITIONED state */
    private PositionInfo position;

    // ============ Cooldown Info (when COOLDOWN) ============

    /** Remaining cooldown time in ms */
    private long cooldownRemainingMs;

    // ============ Stats ============

    /** Number of signals emitted today for this instrument */
    private int signalsToday;

    /** Maximum signals allowed per day */
    private int maxSignalsPerDay;
}
