package com.kotsin.consumer.model;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Timeframe - Supported candle timeframes.
 *
 * Used for:
 * - On-demand aggregation from 1m candles
 * - Redis cache key generation
 * - Window alignment
 */
public enum Timeframe {
    M1("1m", 1),
    M2("2m", 2),
    M3("3m", 3),
    M5("5m", 5),
    M15("15m", 15),
    M30("30m", 30),
    H1("1h", 60),
    H2("2h", 120),
    H4("4h", 240),
    D1("1d", 1440),
    W1("1w", 10080);

    private final String label;
    private final int minutes;

    Timeframe(String label, int minutes) {
        this.label = label;
        this.minutes = minutes;
    }

    public String getLabel() {
        return label;
    }

    public int getMinutes() {
        return minutes;
    }

    public Duration getDuration() {
        return Duration.ofMinutes(minutes);
    }

    /**
     * Align timestamp to window start for this timeframe.
     * Example: 10:17:30 with M5 → 10:15:00
     */
    public Instant alignToWindowStart(Instant timestamp) {
        ZonedDateTime zdt = timestamp.atZone(ZoneId.of("Asia/Kolkata"));

        if (this == D1) {
            // Daily: align to market open (9:15 AM IST)
            return zdt.truncatedTo(ChronoUnit.DAYS)
                .plusHours(9).plusMinutes(15)
                .toInstant();
        }

        if (this == W1) {
            // Weekly: align to Monday 9:15 AM IST
            int dayOfWeek = zdt.getDayOfWeek().getValue();
            return zdt.truncatedTo(ChronoUnit.DAYS)
                .minusDays(dayOfWeek - 1)
                .plusHours(9).plusMinutes(15)
                .toInstant();
        }

        // Intraday: align to minute boundary
        long epochMinute = timestamp.toEpochMilli() / 60000;
        long alignedMinute = (epochMinute / minutes) * minutes;
        return Instant.ofEpochMilli(alignedMinute * 60000);
    }

    /**
     * Get window end from window start.
     */
    public Instant getWindowEnd(Instant windowStart) {
        return windowStart.plus(getDuration());
    }

    /**
     * Check if this is a popular timeframe (pre-computed in Redis).
     */
    public boolean isPopular() {
        return this == M5 || this == M15 || this == M30 ||
               this == H1 || this == H4 || this == D1;
    }

    /**
     * Parse timeframe from string label.
     */
    public static Timeframe fromLabel(String label) {
        for (Timeframe tf : values()) {
            if (tf.label.equalsIgnoreCase(label)) {
                return tf;
            }
        }
        throw new IllegalArgumentException("Unknown timeframe: " + label);
    }
}
