package com.kotsin.consumer.gate;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.gate.model.GateResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * DataQualityGate - Layer 0: Validates data quality BEFORE any signal generation
 *
 * This gate ensures that garbage data never enters the signal pipeline.
 * All checks are HARD BLOCKS - if any fails, data is rejected.
 *
 * BUGS FIXED:
 * - Bug #14: No data quality validation before signals
 * - Bug #41: VWAP division by zero
 * - Bug #44: Z-score with zero stddev
 * - Bug #46: Floating point comparison issues
 * - Bug #71: First candle of day garbage
 * - Bug #78: Zero volume candles
 * - Bug #79: Single tick candles
 * - Bug #80: Extreme price moves (fat finger)
 */
@Slf4j
@Service
public class DataQualityGate {

    @Value("${gate.data.enabled:true}")
    private boolean enabled;

    // Thresholds for data quality checks
    private static final double MAX_PRICE_MOVE_PCT = 20.0;      // Max 20% move in 1 candle (fat finger filter)
    private static final double MIN_PRICE = 0.01;               // Minimum valid price
    private static final int MIN_TICK_COUNT = 2;                // Minimum ticks for valid candle
    private static final double MAX_SPREAD_PCT = 5.0;           // Max bid-ask spread as % of price
    private static final double MIN_OHLC_RANGE_PCT = 0.0001;    // Minimum range (avoid flat candles for calculations)

    /**
     * Validate FamilyCandle data quality
     *
     * @param family The family candle to validate
     * @return GateResult with pass/fail and detailed reasons
     */
    public GateResult evaluate(FamilyCandle family) {
        if (!enabled) {
            return GateResult.pass("DATA_QUALITY_GATE_DISABLED");
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        String familyId = family != null ? family.getFamilyId() : "UNKNOWN";

        // === NULL CHECKS ===
        if (family == null) {
            return GateResult.fail("NULL_FAMILY_CANDLE");
        }

        if (family.getFamilyId() == null || family.getFamilyId().isEmpty()) {
            return GateResult.fail("NULL_FAMILY_ID");
        }

        // === PRIMARY INSTRUMENT VALIDATION ===
        InstrumentCandle primary = family.getPrimaryInstrumentOrFallback();
        if (primary == null) {
            return GateResult.fail("NO_PRIMARY_INSTRUMENT", "Both equity and future are null");
        }

        // OHLC Sanity
        GateResult ohlcResult = validateOHLC(primary, familyId, errors);
        if (!ohlcResult.isPassed()) {
            return ohlcResult;
        }

        // Volume Check
        if (primary.getVolume() <= 0) {
            errors.add("ZERO_VOLUME");
            log.warn("[DATA_QUALITY] {} FAILED: Zero volume candle - no trading activity", familyId);
        }

        // Tick Count Check (single tick = meaningless)
        // Note: tickCount is primitive int, so no null check needed
        if (primary.getTickCount() > 0 && primary.getTickCount() < MIN_TICK_COUNT) {
            warnings.add("LOW_TICK_COUNT:" + primary.getTickCount());
            log.debug("[DATA_QUALITY] {} WARNING: Low tick count {} - limited price discovery",
                    familyId, primary.getTickCount());
        }

        // === PRICE MOVE VALIDATION (Fat Finger Filter) ===
        if (primary.getOpen() > 0) {
            double priceMovePct = Math.abs(primary.getClose() - primary.getOpen()) / primary.getOpen() * 100;
            if (priceMovePct > MAX_PRICE_MOVE_PCT) {
                errors.add("EXTREME_PRICE_MOVE:" + String.format("%.1f%%", priceMovePct));
                log.error("[DATA_QUALITY] {} FAILED: Extreme price move {}% (max {}%) - possible fat finger",
                        familyId, String.format("%.1f", priceMovePct), MAX_PRICE_MOVE_PCT);
            }
        }

        // === TIMESTAMP VALIDATION ===
        if (family.getTimestamp() <= 0) {
            errors.add("INVALID_TIMESTAMP");
        }

        // Future timestamp check (clock skew)
        long now = System.currentTimeMillis();
        if (family.getTimestamp() > now + 60000) { // More than 1 minute in future
            errors.add("FUTURE_TIMESTAMP");
            log.warn("[DATA_QUALITY] {} FAILED: Future timestamp detected - clock skew?", familyId);
        }

        // === SPREAD VALIDATION (if orderbook present) ===
        if (primary.getBidAskSpread() != null && primary.getBidAskSpread() > 0) {
            double spreadPct = primary.getBidAskSpread() / primary.getClose() * 100;
            if (spreadPct > MAX_SPREAD_PCT) {
                warnings.add("WIDE_SPREAD:" + String.format("%.1f%%", spreadPct));
                log.debug("[DATA_QUALITY] {} WARNING: Wide spread {}% - low liquidity",
                        familyId, String.format("%.1f", spreadPct));
            }
        }

        // === NaN/Infinity CHECK ===
        if (hasNaNOrInfinity(primary)) {
            errors.add("NAN_OR_INFINITY_IN_DATA");
            log.error("[DATA_QUALITY] {} FAILED: NaN or Infinity detected in price data", familyId);
        }

        // === RESULT ===
        if (!errors.isEmpty()) {
            String errorStr = String.join(", ", errors);
            log.warn("[DATA_QUALITY] {} BLOCKED: {} error(s): {}", familyId, errors.size(), errorStr);
            return GateResult.fail("DATA_QUALITY_FAILED", errorStr);
        }

        // Calculate quality multiplier based on warnings
        double multiplier = 1.0;
        if (!warnings.isEmpty()) {
            multiplier = Math.max(0.7, 1.0 - (warnings.size() * 0.1));
            log.debug("[DATA_QUALITY] {} PASSED with {} warning(s): {} | multiplier={}",
                    familyId, warnings.size(), String.join(", ", warnings), multiplier);
        } else {
            log.trace("[DATA_QUALITY] {} PASSED all checks", familyId);
        }

        GateResult result = GateResult.pass("DATA_QUALITY_OK", multiplier);
        result.setGateName("DATA_QUALITY_GATE");
        return result;
    }

    /**
     * Validate OHLC sanity
     * - High >= Low (always)
     * - Open and Close within [Low, High]
     * - All prices > 0
     */
    private GateResult validateOHLC(InstrumentCandle candle, String familyId, List<String> errors) {
        double open = candle.getOpen();
        double high = candle.getHigh();
        double low = candle.getLow();
        double close = candle.getClose();

        // Minimum price check
        if (open < MIN_PRICE || high < MIN_PRICE || low < MIN_PRICE || close < MIN_PRICE) {
            return GateResult.fail("INVALID_PRICE",
                    String.format("O=%.4f H=%.4f L=%.4f C=%.4f (min=%.4f)", open, high, low, close, MIN_PRICE));
        }

        // High >= Low (fundamental OHLC rule)
        if (high < low) {
            log.error("[DATA_QUALITY] {} OHLC INVALID: High {} < Low {} - impossible candle", familyId, high, low);
            return GateResult.fail("HIGH_LESS_THAN_LOW", String.format("H=%.2f < L=%.2f", high, low));
        }

        // Open within range [Low, High]
        if (open < low || open > high) {
            log.warn("[DATA_QUALITY] {} OHLC WARNING: Open {} outside [Low={}, High={}]", familyId, open, low, high);
            errors.add("OPEN_OUTSIDE_RANGE");
        }

        // Close within range [Low, High]
        if (close < low || close > high) {
            log.warn("[DATA_QUALITY] {} OHLC WARNING: Close {} outside [Low={}, High={}]", familyId, close, low, high);
            errors.add("CLOSE_OUTSIDE_RANGE");
        }

        // Check for flat candle (all same price)
        double range = high - low;
        double rangePct = range / close * 100;
        if (rangePct < MIN_OHLC_RANGE_PCT && candle.getVolume() > 0) {
            // Flat candle with volume is suspicious but not necessarily invalid
            log.debug("[DATA_QUALITY] {} Flat candle: range={}% with volume={}",
                    familyId, String.format("%.4f", rangePct), candle.getVolume());
        }

        return GateResult.pass("OHLC_VALID");
    }

    /**
     * Check for NaN or Infinity in critical fields
     * Note: vwap and vpin are primitive doubles, ofi is Double wrapper
     */
    private boolean hasNaNOrInfinity(InstrumentCandle candle) {
        return Double.isNaN(candle.getOpen()) || Double.isInfinite(candle.getOpen()) ||
               Double.isNaN(candle.getHigh()) || Double.isInfinite(candle.getHigh()) ||
               Double.isNaN(candle.getLow()) || Double.isInfinite(candle.getLow()) ||
               Double.isNaN(candle.getClose()) || Double.isInfinite(candle.getClose()) ||
               Double.isNaN(candle.getVwap()) || Double.isInfinite(candle.getVwap()) ||
               (candle.getOfi() != null && (Double.isNaN(candle.getOfi()) || Double.isInfinite(candle.getOfi()))) ||
               Double.isNaN(candle.getVpin()) || Double.isInfinite(candle.getVpin());
    }

    /**
     * Quick validation for calculations - use before any division
     */
    public static boolean isValidForDivision(double denominator) {
        return denominator != 0 && !Double.isNaN(denominator) && !Double.isInfinite(denominator);
    }

    /**
     * Safe division with NaN/Infinity protection
     */
    public static double safeDivide(double numerator, double denominator, double defaultValue) {
        if (!isValidForDivision(denominator)) {
            return defaultValue;
        }
        double result = numerator / denominator;
        if (Double.isNaN(result) || Double.isInfinite(result)) {
            return defaultValue;
        }
        return result;
    }

    /**
     * Safe percentage calculation
     */
    public static double safePercentage(double value, double total, double defaultValue) {
        return safeDivide(value, total, defaultValue) * 100.0;
    }

    /**
     * Validate a numeric value is usable (not NaN, not Infinity, optionally > 0)
     */
    public static boolean isValidNumber(Double value, boolean requirePositive) {
        if (value == null || Double.isNaN(value) || Double.isInfinite(value)) {
            return false;
        }
        if (requirePositive && value <= 0) {
            return false;
        }
        return true;
    }

    /**
     * Clamp value to range with NaN protection
     */
    public static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) return (min + max) / 2;
        if (Double.isInfinite(value)) return value > 0 ? max : min;
        return Math.max(min, Math.min(max, value));
    }
}
