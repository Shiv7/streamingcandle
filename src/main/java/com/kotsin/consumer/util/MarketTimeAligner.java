package com.kotsin.consumer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A utility class to align timestamps to market-specific trading windows.
 * This is a core component for ensuring that candlestick windows match the
 * real-world trading schedules of NSE and MCX.
 *
 * CRITICAL: NSE/BSE opens at 9:15 AM, MCX at 9:00 AM
 */
public final class MarketTimeAligner {

    private static final Logger LOGGER = LoggerFactory.getLogger(MarketTimeAligner.class);

    /**
     * BUG-020 FIX: Handle all known exchanges and warn on unknown ones
     */
    public static int getWindowOffsetMinutes(String exchange, int windowSizeMinutes) {
        int base;

        if ("N".equalsIgnoreCase(exchange)) {
            base = 15;  // NSE opens at 9:15 AM
        } else if ("M".equalsIgnoreCase(exchange)) {
            base = 0;   // MCX opens at 9:00 AM
        } else if ("B".equalsIgnoreCase(exchange)) {
            base = 15;  // BSE opens at 9:15 AM (same as NSE)
        } else {
            // Unknown exchange - default to round hours and warn
            base = 0;
            LOGGER.warn("Unknown exchange '{}', using default alignment (round hours). Known exchanges: N (NSE), M (MCX), B (BSE)", exchange);
        }

        // Reduce modulo window size so 2/3/5/15/30 align correctly
        int mod = ((base % windowSizeMinutes) + windowSizeMinutes) % windowSizeMinutes;
        return mod;
    }
}

