package com.kotsin.consumer.signal.model;

/**
 * SignalState - Trading signal lifecycle states.
 *
 * State Machine:
 * IDLE -> WATCH -> ACTIVE -> COMPLETE
 *           |         |
 *           v         v
 *        EXPIRED   (WIN/LOSS/STOP)
 */
public enum SignalState {

    /**
     * IDLE - No signal, scanning for setups.
     * Default state when no conditions are met.
     */
    IDLE("Scanning", false, false),

    /**
     * WATCH - Setup forming, monitoring for trigger.
     * Entry: VCP + IPU + OI alignment detected.
     * Exit: Trigger confirmed -> ACTIVE, or conditions invalidate -> EXPIRED.
     */
    WATCH("Watching", true, false),

    /**
     * ACTIVE - Signal triggered, entry recommended.
     * Entry: Breakout/breakdown confirmed with volume.
     * Exit: Target hit, stop hit, or timeout -> COMPLETE.
     */
    ACTIVE("Active", true, true),

    /**
     * COMPLETE - Signal resolved, tracking outcome.
     * Final state - signal has completed its lifecycle.
     */
    COMPLETE("Complete", false, false),

    /**
     * EXPIRED - Setup invalidated before trigger.
     * Conditions no longer valid (time decay, structure break, etc.)
     */
    EXPIRED("Expired", false, false);

    private final String displayName;
    private final boolean isActive;
    private final boolean isTriggered;

    SignalState(String displayName, boolean isActive, boolean isTriggered) {
        this.displayName = displayName;
        this.isActive = isActive;
        this.isTriggered = isTriggered;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Is this an active state (WATCH or ACTIVE)?
     */
    public boolean isActive() {
        return isActive;
    }

    /**
     * Is this a triggered state (ACTIVE)?
     */
    public boolean isTriggered() {
        return isTriggered;
    }

    /**
     * Is this a terminal state (COMPLETE or EXPIRED)?
     */
    public boolean isTerminal() {
        return this == COMPLETE || this == EXPIRED;
    }

    /**
     * Can transition to the given state?
     */
    public boolean canTransitionTo(SignalState target) {
        return switch (this) {
            case IDLE -> target == WATCH;
            case WATCH -> target == ACTIVE || target == EXPIRED;
            case ACTIVE -> target == COMPLETE;
            case COMPLETE, EXPIRED -> false;
        };
    }
}
