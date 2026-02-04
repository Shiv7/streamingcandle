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
 *
 * Note: Window alignment uses IST (Asia/Kolkata) timezone for Indian market data.
 * NSE Market hours: 9:15 AM - 3:30 PM IST
 * MCX Market hours: 9:00 AM - 11:30 PM IST
 * Daily candles align to market open (9:15 NSE, 9:00 MCX)
 * Weekly candles align to Monday market open
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

    // Market timezone - IST for Indian markets
    public static final ZoneId MARKET_TIMEZONE = ZoneId.of("Asia/Kolkata");

    // NSE market open
    public static final int MARKET_OPEN_HOUR = 9;
    public static final int MARKET_OPEN_MINUTE = 15;

    // MCX market open
    public static final int MCX_MARKET_OPEN_HOUR = 9;
    public static final int MCX_MARKET_OPEN_MINUTE = 0;

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
     * Uses MARKET_TIMEZONE (IST) for daily/weekly alignment
     * Defaults to NSE market open for daily/weekly alignment.
     */
    public Instant alignToWindowStart(Instant timestamp) {
        return alignToWindowStart(timestamp, "N");
    }

    /**
     * Align timestamp to window start for this timeframe (exchange-aware).
     * Example: 10:17:30 with M5 → 10:15:00
     * Uses MARKET_TIMEZONE (IST) for daily/weekly alignment
     *
     * @param timestamp The timestamp to align
     * @param exchange "M" for MCX (9:00 open), "N" for NSE (9:15 open)
     */
    public Instant alignToWindowStart(Instant timestamp, String exchange) {
        ZonedDateTime zdt = timestamp.atZone(MARKET_TIMEZONE);
        boolean isMCX = "M".equalsIgnoreCase(exchange);
        int openHour = isMCX ? MCX_MARKET_OPEN_HOUR : MARKET_OPEN_HOUR;
        int openMinute = isMCX ? MCX_MARKET_OPEN_MINUTE : MARKET_OPEN_MINUTE;

        if (this == D1) {
            // Daily: align to market open (9:00 MCX, 9:15 NSE)
            return zdt.truncatedTo(ChronoUnit.DAYS)
                .plusHours(openHour).plusMinutes(openMinute)
                .toInstant();
        }

        if (this == W1) {
            // Weekly: align to Monday market open
            int dayOfWeek = zdt.getDayOfWeek().getValue();
            return zdt.truncatedTo(ChronoUnit.DAYS)
                .minusDays(dayOfWeek - 1)
                .plusHours(openHour).plusMinutes(openMinute)
                .toInstant();
        }

        // Intraday: align to minute boundary (epoch millis are timezone-agnostic)
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
