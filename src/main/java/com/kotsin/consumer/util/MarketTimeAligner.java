package com.kotsin.consumer.util;

/**
 * A utility class to align timestamps to market-specific trading windows.
 * This is a core component for ensuring that candlestick windows match the
 * real-world trading schedules of NSE and MCX.
 */
public final class MarketTimeAligner {

    public static int getWindowOffsetMinutes(String exchange, int windowSizeMinutes) {
        // NSE opens effectively on :15; MCX on :00
        int base = "N".equalsIgnoreCase(exchange) ? 15 : 0;
        // Reduce modulo window size so 2/3/5/15/30 align correctly
        int mod = ((base % windowSizeMinutes) + windowSizeMinutes) % windowSizeMinutes;
        return mod;
    }
}
