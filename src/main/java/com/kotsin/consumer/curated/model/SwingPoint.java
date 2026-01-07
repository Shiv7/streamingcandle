package com.kotsin.consumer.curated.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SwingPoint - Represents a swing high or low in price structure
 * Used for detecting consolidation patterns (Lower Highs + Higher Lows)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SwingPoint {

    private String scripCode;
    private String timeframe;       // "1m", "2m", "3m"
    private long timestamp;
    private SwingType type;         // HIGH or LOW
    private double price;
    private long volume;
    private int barIndex;           // Position in sequence

    public enum SwingType {
        HIGH,   // Swing high (local peak)
        LOW     // Swing low (local trough)
    }

    /**
     * Check if this swing point is recent (within last N milliseconds)
     */
    public boolean isRecent(long currentTime, long windowMillis) {
        return (currentTime - timestamp) <= windowMillis;
    }
}
