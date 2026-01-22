package com.kotsin.consumer.trading.state;

/**
 * InstrumentState - The lifecycle state of an instrument in the trading system.
 *
 * STATE MACHINE:
 * IDLE → WATCHING → READY → POSITIONED → COOLDOWN → IDLE
 *
 * KEY RULE: Only ONE signal emitted per cycle (on WATCHING → READY transition)
 */
public enum InstrumentState {

    /**
     * IDLE - No setup forming. Instrument is quiet.
     * - No active monitoring
     * - Waiting for a strategy to detect setup forming
     * - Can transition to: WATCHING
     */
    IDLE,

    /**
     * WATCHING - Setup is forming. Heightened alertness.
     * - Price approaching key level (pivot, support, resistance)
     * - Monitoring for entry trigger
     * - NO SIGNALS emitted yet
     * - Can transition to: READY (trigger fires), IDLE (setup invalidates)
     */
    WATCHING,

    /**
     * READY - Entry trigger fired. ONE SIGNAL emitted.
     * - Waiting for trade execution/fill
     * - Signal has been published to Kafka
     * - Can transition to: POSITIONED (fill confirmed), IDLE (signal expires/cancels)
     */
    READY,

    /**
     * POSITIONED - Trade is live. Managing the position.
     * - Tracking P&L in real-time
     * - Monitoring stop loss and targets
     * - NO NEW SIGNALS - only exit management
     * - Can transition to: COOLDOWN (exit triggered)
     */
    POSITIONED,

    /**
     * COOLDOWN - Trade completed. Rest period.
     * - 30-60 minutes of no new setups
     * - Prevents revenge trading, over-trading
     * - Allows instrument to "settle"
     * - Can transition to: IDLE (cooldown expires)
     */
    COOLDOWN
}
