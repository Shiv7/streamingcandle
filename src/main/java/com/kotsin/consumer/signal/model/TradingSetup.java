package com.kotsin.consumer.signal.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * TradingSetup - Tracks setup lifecycle from level detection through retest confirmation.
 *
 * States: LEVEL_WATCHING → APPROACH_DETECTED → RETEST_IN_PROGRESS → RETEST_CONFIRMED / FAILED_RETEST / EXPIRED
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "trading_setups")
@CompoundIndex(name = "symbol_state_idx", def = "{'symbol': 1, 'state': 1}")
@CompoundIndex(name = "state_created_idx", def = "{'state': 1, 'createdAt': -1}")
public class TradingSetup {

    @Id
    private String id;

    @Indexed
    private String symbol;

    // Level details
    private double levelPrice;
    private String levelSource;       // e.g., "RESISTANCE", "SUPPORT", "ORDER_BLOCK"
    private String levelDescription;  // e.g., "Daily_R1", "PDH"

    // Direction: BULLISH (breakout above, retest from above) or BEARISH (breakout below, retest from below)
    private String direction;

    // State machine
    private SetupState state;

    @Builder.Default
    private List<StateTransition> stateHistory = new ArrayList<>();

    // Timing
    private Instant createdAt;
    private Instant lastUpdatedAt;
    private Instant expiresAt;         // Auto-expire if setup doesn't progress

    // Approach detection
    private double approachPrice;
    private Instant approachDetectedAt;

    // Retest tracking
    private double retestPrice;
    private Instant retestStartedAt;
    private String retestQuality;      // PERFECT, GOOD, POOR, FAILED
    private boolean retestHeld;

    // Breakout reference
    private double breakoutPrice;
    private Instant breakoutTime;

    public enum SetupState {
        LEVEL_WATCHING,       // Monitoring a broken level for potential retest
        APPROACH_DETECTED,    // Price approaching the broken level
        RETEST_IN_PROGRESS,   // Price within retest zone
        RETEST_CONFIRMED,     // Retest held — ready for signal
        FAILED_RETEST,        // Price failed through the level
        EXPIRED               // Setup expired without progression
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StateTransition {
        private SetupState fromState;
        private SetupState toState;
        private Instant timestamp;
        private double priceAtTransition;
        private String reason;
    }

    public void transitionTo(SetupState newState, double price, String reason) {
        StateTransition transition = StateTransition.builder()
            .fromState(this.state)
            .toState(newState)
            .timestamp(Instant.now())
            .priceAtTransition(price)
            .reason(reason)
            .build();
        this.stateHistory.add(transition);
        this.state = newState;
        this.lastUpdatedAt = Instant.now();
    }

    public boolean isTerminal() {
        return state == SetupState.RETEST_CONFIRMED
            || state == SetupState.FAILED_RETEST
            || state == SetupState.EXPIRED;
    }
}
