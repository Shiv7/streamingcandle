package com.kotsin.consumer.util;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * A utility class to align timestamps to market-specific trading windows.
 * This is a core component for ensuring that candlestick windows match the
 * real-world trading schedules of NSE and MCX.
 */
public final class MarketTimeAligner {

    private static final ZoneId INDIA_ZONE = ZoneId.of("Asia/Kolkata");

    // NSE Trading Hours
    private static final LocalTime NSE_OPEN_TIME = LocalTime.of(9, 15);

    // MCX Trading Hours
    private static final LocalTime MCX_OPEN_TIME = LocalTime.of(9, 0);

    private MarketTimeAligner() {
        // Private constructor to prevent instantiation
    }

    /**
     * Calculates the correct start time of a trading window for a given timestamp, exchange, and window size.
     *
     * @param recordTime       The timestamp of the event.
     * @param exchange         The exchange ("N" for NSE, "M" for MCX).
     * @param windowSizeMinutes The duration of the candle window in minutes.
     * @return The epoch milliseconds of the calculated window start time.
     */
    public static long getAlignedWindowStart(ZonedDateTime recordTime, String exchange, int windowSizeMinutes) {
        if ("N".equals(exchange)) {
            return getAlignedNseWindow(recordTime, windowSizeMinutes);
        } else {
            // Default to MCX rules for commodities or other exchanges
            return getAlignedMcxWindow(recordTime, windowSizeMinutes);
        }
    }

    private static long getAlignedNseWindow(ZonedDateTime recordTime, int windowSizeMinutes) {
        ZonedDateTime marketOpen = recordTime.with(NSE_OPEN_TIME);

        // If the record time is before today's market open, it belongs to the previous trading day's windows.
        if (recordTime.isBefore(marketOpen)) {
            marketOpen = marketOpen.minusDays(1);
        }

        long minutesElapsed = ChronoUnit.MINUTES.between(marketOpen, recordTime);
        if (minutesElapsed < 0) {
            return marketOpen.toInstant().toEpochMilli(); // Belongs to the first window
        }

        long windowNum = minutesElapsed / windowSizeMinutes;
        ZonedDateTime windowStart = marketOpen.plusMinutes(windowNum * windowSizeMinutes);

        return windowStart.toInstant().toEpochMilli();
    }

    private static long getAlignedMcxWindow(ZonedDateTime recordTime, int windowSizeMinutes) {
        ZonedDateTime marketOpen = recordTime.with(MCX_OPEN_TIME);

        // If the record time is before today's market open, it belongs to the previous trading day's windows.
        if (recordTime.isBefore(marketOpen)) {
            marketOpen = marketOpen.minusDays(1);
        }

        long minutesElapsed = ChronoUnit.MINUTES.between(marketOpen, recordTime);
        if (minutesElapsed < 0) {
            return marketOpen.toInstant().toEpochMilli(); // Belongs to the first window
        }

        long windowNum = minutesElapsed / windowSizeMinutes;
        ZonedDateTime windowStart = marketOpen.plusMinutes(windowNum * windowSizeMinutes);

        return windowStart.toInstant().toEpochMilli();
    }
}
