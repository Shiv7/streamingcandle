package com.kotsin.consumer.domain.model;

/**
 * DataQuality - Enum to indicate data quality/validation status
 * 
 * Used by validators to flag issues with incoming data.
 * Strategy modules should check quality before using metrics.
 */
public enum DataQuality {
    /**
     * All data validated successfully
     */
    VALID,

    /**
     * Data has minor inconsistencies but is usable
     * Example: Trade imbalance and OFI have low correlation
     */
    WARNING,

    /**
     * Data has conflicting signals - use with caution
     * Example: Trade imbalance is positive but OFI is negative
     */
    CONFLICT,

    /**
     * Data is stale (old timestamp) or missing
     */
    STALE,

    /**
     * Insufficient data to validate
     * Example: First candle of the day, no history
     */
    INSUFFICIENT;

    /**
     * Check if this quality level is usable for trading signals
     */
    public boolean isUsableForSignals() {
        return this == VALID || this == WARNING;
    }

    /**
     * Check if this quality level should trigger an alert
     */
    public boolean shouldAlert() {
        return this == CONFLICT || this == STALE;
    }
}
