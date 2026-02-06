package com.kotsin.consumer.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * TimeframeBoundary - Utility for detecting when a 1m candle closes at a higher timeframe boundary.
 *
 * IMPORTANT: This enables EVENT-DRIVEN analysis instead of poll-based.
 * Only when a timeframe boundary is crossed should analysis for that timeframe be triggered.
 *
 * NSE Market Hours: 9:15 AM - 3:30 PM IST
 * MCX Market Hours: 9:00 AM - 11:30 PM IST
 *
 * Boundary Examples (NSE):
 * - 15m: xx:15, xx:30, xx:45, xx:00
 * - 30m: xx:15, xx:45 (NSE style: 9:15-9:45, 9:45-10:15, etc.)
 * - 1h:  xx:15 (aligned to market open at 9:15)
 * - 4h:  13:15 (9:15+4h), 15:30 (market close)
 * - Daily: 15:30 (market close)
 *
 * Boundary Examples (MCX):
 * - 30m: xx:00, xx:30 (MCX style: 9:00-9:30, 9:30-10:00, etc.)
 * - 1h:  xx:00 (aligned to market open at 9:00)
 * - Daily: 23:30 (market close)
 */
public class TimeframeBoundary {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // NSE Market timing
    public static final int MARKET_OPEN_HOUR = 9;
    public static final int MARKET_OPEN_MINUTE = 15;
    public static final int MARKET_CLOSE_HOUR = 15;
    public static final int MARKET_CLOSE_MINUTE = 30;

    // MCX Market timing
    public static final int MCX_MARKET_OPEN_HOUR = 9;
    public static final int MCX_MARKET_OPEN_MINUTE = 0;
    public static final int MCX_MARKET_CLOSE_HOUR = 23;
    public static final int MCX_MARKET_CLOSE_MINUTE = 30;

    /**
     * Check all timeframe boundaries crossed by this 1m candle close.
     * Returns list of timeframes whose windows just closed.
     * Uses NSE market hours by default.
     */
    public static List<Timeframe> getCrossedBoundaries(Instant candleCloseTime) {
        return getCrossedBoundaries(candleCloseTime, "N");
    }

    /**
     * Check all timeframe boundaries crossed by this 1m candle close.
     * Returns list of timeframes whose windows just closed.
     * Exchange-aware version.
     *
     * @param candleCloseTime The candle close time
     * @param exchange "M" for MCX, "N" for NSE (default)
     */
    public static List<Timeframe> getCrossedBoundaries(Instant candleCloseTime, String exchange) {
        List<Timeframe> crossed = new ArrayList<>();
        ZonedDateTime istTime = candleCloseTime.atZone(IST);
        int minute = istTime.getMinute();
        int hour = istTime.getHour();

        boolean isMCX = "M".equalsIgnoreCase(exchange);

        // Skip if outside market hours
        if (!isMarketHours(istTime, exchange)) {
            return crossed;
        }

        // Bug #9: 2m boundary
        if (minute % 2 == 0) {
            crossed.add(Timeframe.M2);
        }

        // Bug #9: 3m boundary
        if (minute % 3 == 0) {
            crossed.add(Timeframe.M3);
        }

        // 5m boundary: xx:00, xx:05, xx:10, xx:15, etc. (same for both)
        if (minute % 5 == 0) {
            crossed.add(Timeframe.M5);
        }

        // 15m boundary: xx:00, xx:15, xx:30, xx:45 (same for both)
        if (minute % 15 == 0) {
            crossed.add(Timeframe.M15);
        }

        // 30m boundary: exchange-dependent
        if (isMCX) {
            // MCX: xx:00 and xx:30
            if (minute == 0 || minute == 30) {
                crossed.add(Timeframe.M30);
            }
        } else {
            // NSE: xx:15 and xx:45
            if (minute == 15 || minute == 45) {
                crossed.add(Timeframe.M30);
            }
        }

        // 1h boundary: exchange-dependent
        if (isMCX) {
            // MCX: xx:00 (aligned to 9:00 market open)
            if (minute == 0) {
                crossed.add(Timeframe.H1);
            }
        } else {
            // NSE: xx:15 (aligned to 9:15 market open)
            if (minute == 15) {
                crossed.add(Timeframe.H1);
            }
        }

        // 2h boundary: exchange-dependent
        if (isMCX) {
            // MCX: 11:00, 13:00, 15:00, 17:00, 19:00, 21:00, 23:00 (starting from 9:00)
            if (minute == 0 && ((hour - MCX_MARKET_OPEN_HOUR) % 2 == 0)) {
                crossed.add(Timeframe.H2);
            }
        } else {
            // NSE: 11:15, 13:15, 15:15 (starting from 9:15)
            if (minute == 15 && ((hour - MARKET_OPEN_HOUR) % 2 == 0)) {
                crossed.add(Timeframe.H2);
            }
        }

        // 4h boundary: exchange-dependent
        if (isMCX) {
            // MCX: 13:00 (9:00 + 4h), 17:00, 21:00, and market close at 23:30
            if ((minute == 0 && ((hour - MCX_MARKET_OPEN_HOUR) % 4 == 0)) ||
                (hour == MCX_MARKET_CLOSE_HOUR && minute == MCX_MARKET_CLOSE_MINUTE)) {
                crossed.add(Timeframe.H4);
            }
        } else {
            // NSE: 13:15 (9:15 + 4h), and market close at 15:30
            if ((hour == 13 && minute == 15) || (hour == MARKET_CLOSE_HOUR && minute == MARKET_CLOSE_MINUTE)) {
                crossed.add(Timeframe.H4);
            }
        }

        // Daily boundary: market close
        if (isMCX) {
            // MCX: market close at 23:30
            if (hour == MCX_MARKET_CLOSE_HOUR && minute == MCX_MARKET_CLOSE_MINUTE) {
                crossed.add(Timeframe.D1);
            }
        } else {
            // NSE: market close at 15:30
            if (hour == MARKET_CLOSE_HOUR && minute == MARKET_CLOSE_MINUTE) {
                crossed.add(Timeframe.D1);
            }
        }

        return crossed;
    }

    /**
     * Check if a specific timeframe boundary is crossed.
     * Uses NSE by default.
     */
    public static boolean isBoundaryCrossed(Instant candleCloseTime, Timeframe timeframe) {
        return getCrossedBoundaries(candleCloseTime, "N").contains(timeframe);
    }

    /**
     * Check if a specific timeframe boundary is crossed.
     * Exchange-aware version.
     */
    public static boolean isBoundaryCrossed(Instant candleCloseTime, Timeframe timeframe, String exchange) {
        return getCrossedBoundaries(candleCloseTime, exchange).contains(timeframe);
    }

    /**
     * Check if 15m boundary is crossed (same for both exchanges).
     */
    public static boolean is15mBoundary(Instant time) {
        int minute = time.atZone(IST).getMinute();
        return minute % 15 == 0;
    }

    /**
     * Check if 30m boundary is crossed (NSE style: xx:15 or xx:45).
     * Uses NSE by default.
     */
    public static boolean is30mBoundary(Instant time) {
        return is30mBoundary(time, "N");
    }

    /**
     * Check if 30m boundary is crossed.
     * Exchange-aware version.
     * MCX: xx:00 or xx:30
     * NSE: xx:15 or xx:45
     */
    public static boolean is30mBoundary(Instant time, String exchange) {
        int minute = time.atZone(IST).getMinute();
        if ("M".equalsIgnoreCase(exchange)) {
            return minute == 0 || minute == 30;
        }
        return minute == 15 || minute == 45;
    }

    /**
     * Check if 1h boundary is crossed (NSE: aligned to 9:15).
     * Uses NSE by default.
     */
    public static boolean is1hBoundary(Instant time) {
        return is1hBoundary(time, "N");
    }

    /**
     * Check if 1h boundary is crossed.
     * Exchange-aware version.
     * MCX: xx:00 (aligned to 9:00)
     * NSE: xx:15 (aligned to 9:15)
     */
    public static boolean is1hBoundary(Instant time, String exchange) {
        int minute = time.atZone(IST).getMinute();
        if ("M".equalsIgnoreCase(exchange)) {
            return minute == 0;
        }
        return minute == 15;
    }

    /**
     * Check if 4h boundary is crossed.
     * Uses NSE by default.
     */
    public static boolean is4hBoundary(Instant time) {
        return is4hBoundary(time, "N");
    }

    /**
     * Check if 4h boundary is crossed.
     * Exchange-aware version.
     */
    public static boolean is4hBoundary(Instant time, String exchange) {
        ZonedDateTime istTime = time.atZone(IST);
        int hour = istTime.getHour();
        int minute = istTime.getMinute();

        if ("M".equalsIgnoreCase(exchange)) {
            // MCX: 13:00, 17:00, 21:00, or market close at 23:30
            return (minute == 0 && ((hour - MCX_MARKET_OPEN_HOUR) % 4 == 0)) ||
                   (hour == MCX_MARKET_CLOSE_HOUR && minute == MCX_MARKET_CLOSE_MINUTE);
        }
        // NSE: 13:15 or market close at 15:30
        return (hour == 13 && minute == 15) ||
               (hour == MARKET_CLOSE_HOUR && minute == MARKET_CLOSE_MINUTE);
    }

    /**
     * Check if daily boundary is crossed (market close).
     * Uses NSE by default.
     */
    public static boolean isDailyBoundary(Instant time) {
        return isDailyBoundary(time, "N");
    }

    /**
     * Check if daily boundary is crossed (market close).
     * Exchange-aware version.
     */
    public static boolean isDailyBoundary(Instant time, String exchange) {
        ZonedDateTime istTime = time.atZone(IST);
        int hour = istTime.getHour();
        int minute = istTime.getMinute();

        if ("M".equalsIgnoreCase(exchange)) {
            return hour == MCX_MARKET_CLOSE_HOUR && minute == MCX_MARKET_CLOSE_MINUTE;
        }
        return hour == MARKET_CLOSE_HOUR && minute == MARKET_CLOSE_MINUTE;
    }

    /**
     * Check if time is within market hours (NSE by default).
     */
    public static boolean isMarketHours(Instant time) {
        return isMarketHours(time.atZone(IST), "N");
    }

    /**
     * Check if time is within market hours (NSE by default).
     */
    public static boolean isMarketHours(ZonedDateTime istTime) {
        return isMarketHours(istTime, "N");
    }

    /**
     * Check if time is within market hours.
     * Exchange-aware version.
     *
     * @param time Instant to check
     * @param exchange "M" for MCX (9:00-23:30), "N" for NSE (9:15-15:30)
     */
    public static boolean isMarketHours(Instant time, String exchange) {
        return isMarketHours(time.atZone(IST), exchange);
    }

    /**
     * Check if time is within market hours.
     * Exchange-aware version.
     *
     * @param istTime ZonedDateTime in IST
     * @param exchange "M" for MCX (9:00-23:30), "N" for NSE (9:15-15:30)
     */
    public static boolean isMarketHours(ZonedDateTime istTime, String exchange) {
        int hour = istTime.getHour();
        int minute = istTime.getMinute();
        int totalMinutes = hour * 60 + minute;

        if ("M".equalsIgnoreCase(exchange)) {
            // MCX: 9:00 AM - 11:30 PM
            int mcxOpenMinutes = MCX_MARKET_OPEN_HOUR * 60 + MCX_MARKET_OPEN_MINUTE;   // 540 (9:00)
            int mcxCloseMinutes = MCX_MARKET_CLOSE_HOUR * 60 + MCX_MARKET_CLOSE_MINUTE; // 1410 (23:30)
            return totalMinutes >= mcxOpenMinutes && totalMinutes <= mcxCloseMinutes;
        }

        // NSE: 9:15 AM - 3:30 PM
        int marketOpenMinutes = MARKET_OPEN_HOUR * 60 + MARKET_OPEN_MINUTE;   // 555 (9:15)
        int marketCloseMinutes = MARKET_CLOSE_HOUR * 60 + MARKET_CLOSE_MINUTE; // 930 (15:30)
        return totalMinutes >= marketOpenMinutes && totalMinutes <= marketCloseMinutes;
    }

    /**
     * Get the window start for a specific timeframe at a given time.
     */
    public static Instant getWindowStart(Instant time, Timeframe timeframe) {
        return timeframe.alignToWindowStart(time);
    }

    /**
     * Get the window end for a specific timeframe at a given time.
     */
    public static Instant getWindowEnd(Instant time, Timeframe timeframe) {
        Instant windowStart = getWindowStart(time, timeframe);
        return timeframe.getWindowEnd(windowStart);
    }

    /**
     * Calculate minutes until next boundary for a timeframe.
     * Useful for scheduling.
     */
    public static int minutesUntilNextBoundary(Instant time, Timeframe timeframe) {
        Instant windowEnd = getWindowEnd(time, timeframe);
        long seconds = windowEnd.getEpochSecond() - time.getEpochSecond();
        return (int) (seconds / 60);
    }
}
