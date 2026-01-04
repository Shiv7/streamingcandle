package com.kotsin.consumer.domain.validator;

import com.kotsin.consumer.domain.model.InstrumentCandle;

import com.kotsin.consumer.monitoring.DataQualityMetrics;
import lombok.extern.slf4j.Slf4j;

/**
 * OHLCValidator - Centralized OHLC data validation
 *
 * Validates that OHLC (Open, High, Low, Close) data conforms to basic market rules:
 * - High >= Low
 * - High >= Open, Close
 * - Low <= Open, Close
 * - Close > 0
 *
 * Logs violations and records metrics for monitoring data quality.
 */
@Slf4j
public class OHLCValidator {

    /**
     * Validate OHLC data and record violations
     *
     * @param candle The candle to validate
     * @param instrumentType Type description (e.g., "EQUITY", "FUTURE", "OPTION")
     * @param identifier Identifier for logging (e.g., scripCode, familyId)
     * @param metrics Optional metrics recorder (can be null)
     * @return true if valid, false if any violations found
     */
    public static boolean validate(InstrumentCandle candle,
                                   String instrumentType,
                                   String identifier,
                                   DataQualityMetrics metrics) {
        if (candle == null) {
            log.warn("Cannot validate null candle for {} {}", instrumentType, identifier);
            return false;
        }

        double o = candle.getOpen();
        double h = candle.getHigh();
        double l = candle.getLow();
        double c = candle.getClose();
        boolean valid = true;

        // Check High >= Low
        if (h < l) {
            log.error("🚨 OHLC INVALID | {} {} | high={} < low={}",
                instrumentType, identifier, h, l);
            if (metrics != null) {
                metrics.recordViolation("OHLC_HIGH_LESS_THAN_LOW");
            }
            valid = false;
        }

        // Check High is highest
        if (h < o || h < c) {
            log.error("🚨 HIGH NOT HIGHEST | {} {} | O={} H={} L={} C={}",
                instrumentType, identifier, o, h, l, c);
            if (metrics != null) {
                metrics.recordViolation("HIGH_NOT_HIGHEST");
            }
            valid = false;
        }

        // Check Low is lowest
        if (l > o || l > c) {
            log.error("🚨 LOW NOT LOWEST | {} {} | O={} H={} L={} C={}",
                instrumentType, identifier, o, h, l, c);
            if (metrics != null) {
                metrics.recordViolation("LOW_NOT_LOWEST");
            }
            valid = false;
        }

        // Check for zero/negative close
        if (c <= 0) {
            log.error("🚨 INVALID CLOSE | {} {} | close={}",
                instrumentType, identifier, c);
            if (metrics != null) {
                metrics.recordViolation("INVALID_CLOSE");
            }
            valid = false;
        }

        return valid;
    }

    /**
     * Validate OHLC data without metrics recording
     * Convenience method for cases where metrics aren't needed
     */
    public static boolean validate(InstrumentCandle candle,
                                   String instrumentType,
                                   String identifier) {
        return validate(candle, instrumentType, identifier, null);
    }

    /**
     * Check if candle range is suspiciously small for the timeframe
     * Useful for detecting data issues in larger timeframes
     *
     * @param candle The candle to check
     * @param timeframe Timeframe (e.g., "1h", "2h", "1d")
     * @param identifier Identifier for logging
     * @param minRangePercent Minimum expected range as percentage (e.g., 0.1 for 0.1%)
     * @return true if range is acceptable, false if suspiciously small
     */
    public static boolean checkRangeForTimeframe(InstrumentCandle candle,
                                                 String timeframe,
                                                 String identifier,
                                                 double minRangePercent) {
        if (candle == null || candle.getClose() <= 0) {
            return false;
        }

        double range = candle.getHigh() - candle.getLow();
        double rangePercent = (range / candle.getClose()) * 100;

        if (rangePercent < minRangePercent) {
            log.warn("⚠️ {} candle {} has tiny range: {}% | OHLC={}/{}/{}/{}",
                timeframe, identifier,
                String.format("%.3f", rangePercent),
                candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose());
            return false;
        }

        return true;
    }

    /**
     * Quick validation - just returns true/false without logging
     * Useful for filtering without verbose logging
     */
    public static boolean isValid(InstrumentCandle candle) {
        if (candle == null) return false;

        double o = candle.getOpen();
        double h = candle.getHigh();
        double l = candle.getLow();
        double c = candle.getClose();

        return h >= l && h >= o && h >= c && l <= o && l <= c && c > 0;
    }
}
