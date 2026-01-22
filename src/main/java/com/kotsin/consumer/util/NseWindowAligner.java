package com.kotsin.consumer.util;

import java.time.*;

/**
 * NseWindowAligner - Aligns candle windows to NSE market hours.
 *
 * NSE trading hours: 9:15 AM - 3:30 PM IST
 *
 * PROBLEM: Standard epoch-aligned windows create:
 * - 30m: 9:00-9:30, 9:30-10:00, 10:00-10:30...
 * - But NSE opens at 9:15, so first candle (9:00-9:30) only has 15 min of data!
 *
 * SOLUTION: Align windows to NSE market open (9:15 AM IST):
 * - 30m: 9:15-9:45, 9:45-10:15, 10:15-10:45...
 * - 1h:  9:15-10:15, 10:15-11:15, 11:15-12:15...
 * - 15m: 9:15-9:30, 9:30-9:45, 9:45-10:00... (naturally aligned since 15 divides into 15)
 *
 * This ensures every candle has complete data for accurate indicator calculation.
 */
public class NseWindowAligner {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // NSE market open time: 9:15 AM IST
    private static final int MARKET_OPEN_HOUR = 9;
    private static final int MARKET_OPEN_MINUTE = 15;

    // Offset from the hour (15 minutes)
    private static final long NSE_OFFSET_MS = 15 * 60 * 1000L;  // 900,000 ms

    // IST offset from UTC (5 hours 30 minutes)
    private static final long IST_OFFSET_MS = (5 * 60 + 30) * 60 * 1000L;  // 19,800,000 ms

    // One day in milliseconds
    private static final long DAY_MS = 24 * 60 * 60 * 1000L;

    /**
     * Calculate the NSE-aligned window start for a given epoch timestamp and period.
     *
     * For 30-minute windows:
     * - Input: 9:20 AM IST -> Window: 9:15-9:45 -> Returns: 9:15 AM epoch
     * - Input: 9:50 AM IST -> Window: 9:45-10:15 -> Returns: 9:45 AM epoch
     *
     * @param epochMs    Epoch timestamp in milliseconds (UTC)
     * @param periodMs   Window period in milliseconds (e.g., 30*60*1000 for 30m)
     * @return Window start epoch timestamp (UTC) aligned to NSE market hours
     */
    public static long getAlignedWindowStart(long epochMs, long periodMs) {
        // Convert to IST
        ZonedDateTime zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), IST);

        // Get time components in IST
        int hour = zdt.getHour();
        int minute = zdt.getMinute();
        int second = zdt.getSecond();
        int nano = zdt.getNano();

        // Calculate total minutes since midnight IST
        int totalMinutes = hour * 60 + minute;

        // Calculate minutes since market open (9:15)
        int marketOpenMinutes = MARKET_OPEN_HOUR * 60 + MARKET_OPEN_MINUTE;  // 555 minutes

        // Period in minutes
        int periodMinutes = (int) (periodMs / (60 * 1000L));

        // Calculate which window this timestamp falls into
        // Formula: align to NSE offset (15 minutes past the hour)
        // windowStartMinutes = ((totalMinutes - 15) / periodMinutes) * periodMinutes + 15

        int windowStartMinutes;
        if (periodMinutes <= 0) {
            windowStartMinutes = totalMinutes;
        } else {
            // For windows aligned to :15 and :45 (for 30m), :15, :30, :45, :00 (for 15m), etc.
            int adjustedMinutes = totalMinutes - 15;  // Offset by 15 to align to :15
            if (adjustedMinutes < 0) {
                adjustedMinutes += 24 * 60;  // Handle pre-midnight case
            }
            int windowIndex = adjustedMinutes / periodMinutes;
            windowStartMinutes = windowIndex * periodMinutes + 15;

            // Handle wrap-around past midnight
            windowStartMinutes = windowStartMinutes % (24 * 60);
        }

        // Construct the window start time in IST
        int windowHour = windowStartMinutes / 60;
        int windowMinute = windowStartMinutes % 60;

        // Create window start datetime
        LocalDate date = zdt.toLocalDate();
        LocalTime windowTime = LocalTime.of(windowHour, windowMinute, 0, 0);
        ZonedDateTime windowStart = ZonedDateTime.of(date, windowTime, IST);

        // If window start is after the current time (shouldn't happen normally), go to previous window
        if (windowStart.isAfter(zdt)) {
            windowStart = windowStart.minusMinutes(periodMinutes);
        }

        return windowStart.toInstant().toEpochMilli();
    }

    /**
     * Calculate the NSE-aligned window end for a given epoch timestamp and period.
     *
     * @param epochMs    Epoch timestamp in milliseconds (UTC)
     * @param periodMs   Window period in milliseconds
     * @return Window end epoch timestamp (UTC)
     */
    public static long getAlignedWindowEnd(long epochMs, long periodMs) {
        return getAlignedWindowStart(epochMs, periodMs) + periodMs;
    }

    /**
     * Simple formula for NSE-aligned window calculation.
     * Uses modular arithmetic without full timezone conversion.
     *
     * This is faster but equivalent to getAlignedWindowStart().
     *
     * @param epochMs    Epoch timestamp in milliseconds (UTC)
     * @param periodMs   Window period in milliseconds
     * @return Window start epoch timestamp (UTC) aligned to NSE market hours
     */
    public static long getAlignedWindowStartFast(long epochMs, long periodMs) {
        // Convert epoch to IST milliseconds since midnight
        // IST = UTC + 5:30
        long istMs = epochMs + IST_OFFSET_MS;
        long msSinceMidnightIst = istMs % DAY_MS;

        // Calculate window start (aligned to :15)
        // windowStart = floor((ms - 15min) / period) * period + 15min
        long adjustedMs = msSinceMidnightIst - NSE_OFFSET_MS;
        if (adjustedMs < 0) {
            adjustedMs += DAY_MS;
        }

        long windowStartMsSinceMidnight = (adjustedMs / periodMs) * periodMs + NSE_OFFSET_MS;

        // Handle case where window start wraps around midnight
        if (windowStartMsSinceMidnight >= DAY_MS) {
            windowStartMsSinceMidnight -= DAY_MS;
        }

        // Calculate the midnight epoch for this IST day
        long midnightIstEpoch = (istMs / DAY_MS) * DAY_MS - IST_OFFSET_MS;

        // Window start in epoch
        return midnightIstEpoch + windowStartMsSinceMidnight;
    }

    /**
     * Check if a timestamp falls within NSE market hours (9:15 AM - 3:30 PM IST).
     *
     * @param epochMs Epoch timestamp in milliseconds (UTC)
     * @return true if within market hours
     */
    public static boolean isWithinMarketHours(long epochMs) {
        ZonedDateTime zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), IST);
        LocalTime time = zdt.toLocalTime();

        LocalTime marketOpen = LocalTime.of(9, 15);
        LocalTime marketClose = LocalTime.of(15, 30);

        return !time.isBefore(marketOpen) && !time.isAfter(marketClose);
    }

    /**
     * Get NSE market open time for a given date.
     *
     * @param epochMs Any epoch timestamp on the desired date
     * @return Epoch timestamp of 9:15 AM IST on that date
     */
    public static long getMarketOpenEpoch(long epochMs) {
        ZonedDateTime zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), IST);
        ZonedDateTime marketOpen = zdt.toLocalDate().atTime(9, 15).atZone(IST);
        return marketOpen.toInstant().toEpochMilli();
    }

    /**
     * Get NSE market close time for a given date.
     *
     * @param epochMs Any epoch timestamp on the desired date
     * @return Epoch timestamp of 3:30 PM IST on that date
     */
    public static long getMarketCloseEpoch(long epochMs) {
        ZonedDateTime zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), IST);
        ZonedDateTime marketClose = zdt.toLocalDate().atTime(15, 30).atZone(IST);
        return marketClose.toInstant().toEpochMilli();
    }

    /**
     * Format epoch timestamp as human-readable IST time.
     *
     * @param epochMs Epoch timestamp in milliseconds
     * @return Formatted string like "09:15:00 IST"
     */
    public static String formatAsIst(long epochMs) {
        ZonedDateTime zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), IST);
        return zdt.toLocalTime().toString() + " IST";
    }

    /**
     * Test the alignment with sample timestamps.
     * For debugging/verification.
     */
    public static void main(String[] args) {
        // Test with some sample IST times
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        LocalDate today = LocalDate.now();

        // Test cases: time in IST -> expected window start
        long[][] testCases = {
            // time (hour, minute), expected window start (hour, minute) for 30m
            {9, 15, 9, 15},   // 9:15 -> 9:15
            {9, 20, 9, 15},   // 9:20 -> 9:15
            {9, 44, 9, 15},   // 9:44 -> 9:15
            {9, 45, 9, 45},   // 9:45 -> 9:45
            {10, 0, 9, 45},   // 10:00 -> 9:45
            {10, 14, 9, 45},  // 10:14 -> 9:45
            {10, 15, 10, 15}, // 10:15 -> 10:15
            {10, 30, 10, 15}, // 10:30 -> 10:15
            {15, 15, 15, 15}, // 15:15 -> 15:15
            {15, 30, 15, 15}, // 15:30 -> 15:15 (market close)
        };

        long periodMs = 30 * 60 * 1000L;  // 30 minutes

        System.out.println("=== NSE Window Alignment Test (30m) ===");
        System.out.println("Input Time (IST) -> Window Start (IST) | Expected");
        System.out.println("------------------------------------------------");

        for (long[] tc : testCases) {
            int hour = (int) tc[0];
            int minute = (int) tc[1];
            int expectedHour = (int) tc[2];
            int expectedMinute = (int) tc[3];

            ZonedDateTime input = ZonedDateTime.of(today, LocalTime.of(hour, minute), ist);
            long epochMs = input.toInstant().toEpochMilli();

            long windowStartEpoch = getAlignedWindowStart(epochMs, periodMs);
            ZonedDateTime windowStart = ZonedDateTime.ofInstant(Instant.ofEpochMilli(windowStartEpoch), ist);

            String expected = String.format("%02d:%02d", expectedHour, expectedMinute);
            String actual = windowStart.toLocalTime().toString().substring(0, 5);
            String status = expected.equals(actual) ? "OK" : "FAIL";

            System.out.printf("%02d:%02d -> %s | %s [%s]%n",
                    hour, minute, actual, expected, status);
        }

        // Test 1h alignment
        System.out.println("\n=== NSE Window Alignment Test (1h) ===");
        periodMs = 60 * 60 * 1000L;  // 1 hour

        long[][] test1h = {
            {9, 15, 9, 15},
            {9, 45, 9, 15},
            {10, 0, 9, 15},
            {10, 14, 9, 15},
            {10, 15, 10, 15},
            {11, 0, 10, 15},
            {11, 14, 10, 15},
            {11, 15, 11, 15},
        };

        for (long[] tc : test1h) {
            int hour = (int) tc[0];
            int minute = (int) tc[1];
            int expectedHour = (int) tc[2];
            int expectedMinute = (int) tc[3];

            ZonedDateTime input = ZonedDateTime.of(today, LocalTime.of(hour, minute), ist);
            long epochMs = input.toInstant().toEpochMilli();

            long windowStartEpoch = getAlignedWindowStart(epochMs, periodMs);
            ZonedDateTime windowStart = ZonedDateTime.ofInstant(Instant.ofEpochMilli(windowStartEpoch), ist);

            String expected = String.format("%02d:%02d", expectedHour, expectedMinute);
            String actual = windowStart.toLocalTime().toString().substring(0, 5);
            String status = expected.equals(actual) ? "OK" : "FAIL";

            System.out.printf("%02d:%02d -> %s | %s [%s]%n",
                    hour, minute, actual, expected, status);
        }
    }
}
