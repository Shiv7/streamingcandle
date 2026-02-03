package com.kotsin.consumer.indicator.model;

import com.kotsin.consumer.indicator.model.BBSuperTrend.TrendDirection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * SuperTrendState - Persists SuperTrend calculation state across restarts.
 *
 * This ensures that SuperTrend direction is preserved even when the application
 * restarts, preventing false flip detection due to recalculation from scratch.
 *
 * Key fields:
 * - trend: Current trend direction (UP or DOWN)
 * - superTrendValue: Current SuperTrend line value
 * - finalUpperBand/finalLowerBand: Band values for next calculation
 * - lastCandleWindowStart: Reference to the last processed candle
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "supertrend_state")
@CompoundIndex(name = "scripCode_timeframe_unique_idx",
               def = "{'scripCode': 1, 'timeframe': 1}",
               unique = true)
public class SuperTrendState {

    @Id
    private String id;

    // Identity
    private String scripCode;
    private String timeframe;          // "30m", "15m", etc.

    // Timestamps
    private Instant lastUpdated;
    private Instant lastCandleWindowStart;

    // Core SuperTrend values
    private TrendDirection trend;      // UP or DOWN
    private double superTrendValue;    // Current ST line value
    private double finalUpperBand;
    private double finalLowerBand;

    // ATR for next calculation
    private double lastAtr;

    // For flip detection and tracking
    private Instant lastFlipTime;
    private int barsInCurrentTrend;

    // Last candle reference for validation
    private double lastClose;
    private double lastHigh;
    private double lastLow;

    /**
     * Check if this state is still valid for the given candle time.
     * State is valid if lastCandleWindowStart is before the new candle.
     */
    public boolean isValidForCandle(Instant newCandleWindowStart) {
        if (lastCandleWindowStart == null || newCandleWindowStart == null) {
            return false;
        }
        return lastCandleWindowStart.isBefore(newCandleWindowStart);
    }

    /**
     * Check if state is stale (older than specified duration).
     */
    public boolean isStale(java.time.Duration maxAge) {
        if (lastUpdated == null) return true;
        return Instant.now().isAfter(lastUpdated.plus(maxAge));
    }
}
