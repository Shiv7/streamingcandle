package com.kotsin.consumer.signal.model;

/**
 * SignalStatus - Tracks the lifecycle state of a trading signal.
 *
 * SIGNAL LIFECYCLE:
 * ┌─────────────────────────────────────────────────────────────┐
 * │  ACTIVE → STRENGTHENING → (back to ACTIVE or WEAKENING)    │
 * │    ↓                                                        │
 * │  WEAKENING → INVALID_* (various reasons)                   │
 * │    ↓                                                        │
 * │  EXECUTED (if trade taken)                                  │
 * └─────────────────────────────────────────────────────────────┘
 */
public enum SignalStatus {

    // ============ ACTIVE STATES ============

    /**
     * Signal is valid and conditions still met
     */
    ACTIVE,

    /**
     * Signal is improving (divergence/momentum increasing)
     * Decay multiplier > 1.1
     */
    STRENGTHENING,

    /**
     * Signal is degrading but still valid
     * Decay multiplier between 0.15 and 0.5
     */
    WEAKENING,

    // ============ EXECUTED STATE ============

    /**
     * Signal was executed (trade taken)
     */
    EXECUTED,

    // ============ INVALID STATES ============

    /**
     * Signal expired due to time (> 60 minutes)
     */
    INVALID_TIME_EXPIRED,

    /**
     * Target partially achieved (> 25% of T1)
     * Edge already consumed - too late to enter
     */
    INVALID_TARGET_PARTIALLY_ACHIEVED,

    /**
     * Stop loss level was breached
     */
    INVALID_STOP_BREACHED,

    /**
     * Divergence that created the signal has resolved (< 20% of initial)
     */
    INVALID_DIVERGENCE_RESOLVED,

    /**
     * Divergence has reversed (opposite sign from initial)
     */
    INVALID_DIVERGENCE_REVERSED,

    /**
     * Momentum reversed for 3+ candles
     */
    INVALID_MOMENTUM_REVERSED,

    /**
     * Combined decay fell below floor (< 15%)
     */
    INVALID_DECAYED_TOO_MUCH,

    /**
     * Volume dried up (< 30% of initial)
     */
    INVALID_VOLUME_DRIED,

    /**
     * HTF bias changed against signal direction
     */
    INVALID_HTF_BIAS_CHANGED,

    /**
     * Index correlation flipped against signal
     */
    INVALID_INDEX_CORRELATION_FLIPPED,

    /**
     * Price moved too far without entry (> 3 ATR)
     */
    INVALID_PRICE_MOVED_TOO_FAR,

    /**
     * Session changed (morning signal, now afternoon)
     */
    INVALID_SESSION_CHANGED,

    /**
     * Manually cancelled
     */
    CANCELLED;

    /**
     * Check if this status represents an active (tradeable) signal
     */
    public boolean isActive() {
        return this == ACTIVE || this == STRENGTHENING || this == WEAKENING;
    }

    /**
     * Check if this status represents an invalid signal
     */
    public boolean isInvalid() {
        return name().startsWith("INVALID_") || this == CANCELLED;
    }

    /**
     * Check if signal was executed
     */
    public boolean isExecuted() {
        return this == EXECUTED;
    }

    /**
     * Get human-readable description
     */
    public String getDescription() {
        return switch (this) {
            case ACTIVE -> "Signal active - conditions met";
            case STRENGTHENING -> "Signal strengthening - improving conditions";
            case WEAKENING -> "Signal weakening - degrading but valid";
            case EXECUTED -> "Signal executed - trade taken";
            case INVALID_TIME_EXPIRED -> "Expired - signal too old (>60min)";
            case INVALID_TARGET_PARTIALLY_ACHIEVED -> "Edge consumed - price moved 25%+ toward target";
            case INVALID_STOP_BREACHED -> "Stop breached - signal invalidated";
            case INVALID_DIVERGENCE_RESOLVED -> "Divergence resolved - premise gone";
            case INVALID_DIVERGENCE_REVERSED -> "Divergence reversed - opposite signal now";
            case INVALID_MOMENTUM_REVERSED -> "Momentum reversed - trend changed";
            case INVALID_DECAYED_TOO_MUCH -> "Decayed too much - combined decay <15%";
            case INVALID_VOLUME_DRIED -> "Volume dried up - no participation";
            case INVALID_HTF_BIAS_CHANGED -> "HTF bias changed - structure broke";
            case INVALID_INDEX_CORRELATION_FLIPPED -> "Index flipped - market context changed";
            case INVALID_PRICE_MOVED_TOO_FAR -> "Price moved too far - missed entry window";
            case INVALID_SESSION_CHANGED -> "Session changed - context different";
            case CANCELLED -> "Manually cancelled";
        };
    }
}
