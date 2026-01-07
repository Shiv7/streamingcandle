package com.kotsin.consumer.domain.validator;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.domain.model.OptionCandle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * DataSanityValidator - Centralized validation for candle data
 * 
 * Provides comprehensive validation checks for:
 * - OHLC relationships
 * - OI sanity
 * - Volume sanity
 * - Price sanity
 * - Time consistency
 * 
 * Usage:
 *   List<String> violations = DataSanityValidator.validate(candle);
 *   if (!violations.isEmpty()) {
 *       log.error("Validation failed: {}", violations);
 *   }
 */
@Component
@Slf4j
public class DataSanityValidator {

    // ==================== INSTRUMENT CANDLE VALIDATION ====================

    /**
     * Validate InstrumentCandle and return list of violations
     */
    public static List<String> validate(InstrumentCandle candle) {
        List<String> violations = new ArrayList<>();
        
        if (candle == null) {
            violations.add("Candle is null");
            return violations;
        }
        
        String id = candle.getScripCode() != null ? candle.getScripCode() : "UNKNOWN";
        
        // OHLC validation
        validateOHLC(candle.getOpen(), candle.getHigh(), candle.getLow(), candle.getClose(), id, violations);
        
        // Volume validation
        validateVolume(candle.getVolume(), candle.getBuyVolume(), candle.getSellVolume(), id, violations);
        
        // OI validation (if present)
        if (candle.isOiPresent()) {
            validateOI(candle, id, violations);
        }
        
        // Time validation
        validateTimeWindow(candle.getWindowStartMillis(), candle.getWindowEndMillis(), id, violations);
        
        return violations;
    }

    /**
     * Validate OHLC relationships
     * Rules:
     * - High >= Low
     * - High >= Open, High >= Close
     * - Low <= Open, Low <= Close
     * - Close > 0
     */
    private static void validateOHLC(double open, double high, double low, double close, String id, List<String> violations) {
        // Check close is positive
        if (close <= 0) {
            violations.add(String.format("%s: Close price is non-positive: %.4f", id, close));
        }
        
        // Check High >= Low
        if (high < low) {
            violations.add(String.format("%s: High (%.4f) < Low (%.4f)", id, high, low));
        }
        
        // Check High is highest
        if (high < open) {
            violations.add(String.format("%s: High (%.4f) < Open (%.4f)", id, high, open));
        }
        if (high < close) {
            violations.add(String.format("%s: High (%.4f) < Close (%.4f)", id, high, close));
        }
        
        // Check Low is lowest
        if (low > open) {
            violations.add(String.format("%s: Low (%.4f) > Open (%.4f)", id, low, open));
        }
        if (low > close) {
            violations.add(String.format("%s: Low (%.4f) > Close (%.4f)", id, low, close));
        }
        
        // Check for zero/negative prices
        if (open <= 0 || high <= 0 || low <= 0) {
            violations.add(String.format("%s: Non-positive price detected O=%.4f H=%.4f L=%.4f C=%.4f", 
                id, open, high, low, close));
        }
    }

    /**
     * Validate volume data
     * Rules:
     * - Volume >= 0
     * - BuyVolume >= 0, SellVolume >= 0
     * - BuyVolume + SellVolume should be close to Volume (if classified)
     */
    private static void validateVolume(long volume, long buyVolume, long sellVolume, String id, List<String> violations) {
        if (volume < 0) {
            violations.add(String.format("%s: Negative volume: %d", id, volume));
        }
        if (buyVolume < 0) {
            violations.add(String.format("%s: Negative buy volume: %d", id, buyVolume));
        }
        if (sellVolume < 0) {
            violations.add(String.format("%s: Negative sell volume: %d", id, sellVolume));
        }
        
        // Check buy + sell equals total (with tolerance for unclassified)
        long classifiedVolume = buyVolume + sellVolume;
        if (volume > 0 && classifiedVolume > volume * 1.1) {  // 10% tolerance
            violations.add(String.format("%s: Classified volume (%d) > Total volume (%d)", 
                id, classifiedVolume, volume));
        }
    }

    /**
     * Validate OI (Open Interest) data
     * Rules:
     * - OI values >= 0
     * - OI High >= OI Low
     */
    private static void validateOI(InstrumentCandle candle, String id, List<String> violations) {
        Long oiOpen = candle.getOiOpen();
        Long oiHigh = candle.getOiHigh();
        Long oiLow = candle.getOiLow();
        Long oiClose = candle.getOiClose();
        
        // Check non-negative
        if (oiOpen != null && oiOpen < 0) {
            violations.add(String.format("%s: Negative OI Open: %d", id, oiOpen));
        }
        if (oiClose != null && oiClose < 0) {
            violations.add(String.format("%s: Negative OI Close: %d", id, oiClose));
        }
        
        // Check OI High >= OI Low
        if (oiHigh != null && oiLow != null && oiHigh < oiLow) {
            violations.add(String.format("%s: OI High (%d) < OI Low (%d)", id, oiHigh, oiLow));
        }
        
        // Check OI High is highest
        if (oiHigh != null && oiOpen != null && oiHigh < oiOpen) {
            violations.add(String.format("%s: OI High (%d) < OI Open (%d)", id, oiHigh, oiOpen));
        }
        if (oiHigh != null && oiClose != null && oiHigh < oiClose) {
            violations.add(String.format("%s: OI High (%d) < OI Close (%d)", id, oiHigh, oiClose));
        }
    }

    /**
     * Validate time window
     * Rules:
     * - End time > Start time
     * - Window size should be reasonable (< 24 hours)
     */
    private static void validateTimeWindow(long startMillis, long endMillis, String id, List<String> violations) {
        if (endMillis <= startMillis) {
            violations.add(String.format("%s: Window end (%d) <= start (%d)", id, endMillis, startMillis));
        }
        
        long windowSizeMs = endMillis - startMillis;
        long oneDayMs = 24 * 60 * 60 * 1000L;
        if (windowSizeMs > oneDayMs) {
            violations.add(String.format("%s: Window size too large: %d ms (> 24h)", id, windowSizeMs));
        }
    }

    // ==================== FAMILY CANDLE VALIDATION ====================

    /**
     * Validate FamilyCandle and return list of violations
     */
    public static List<String> validate(FamilyCandle familyCandle) {
        List<String> violations = new ArrayList<>();
        
        if (familyCandle == null) {
            violations.add("FamilyCandle is null");
            return violations;
        }
        
        String familyId = familyCandle.getFamilyId() != null ? familyCandle.getFamilyId() : "UNKNOWN";
        
        // Validate equity (mandatory)
        if (familyCandle.getEquity() == null) {
            violations.add(String.format("%s: Missing equity candle", familyId));
        } else {
            List<String> equityViolations = validate(familyCandle.getEquity());
            equityViolations.forEach(v -> violations.add("EQUITY: " + v));
        }
        
        // Validate future (if present)
        if (familyCandle.getFuture() != null) {
            List<String> futureViolations = validate(familyCandle.getFuture());
            futureViolations.forEach(v -> violations.add("FUTURE: " + v));
        }
        
        // Validate family window times
        validateTimeWindow(familyCandle.getWindowStartMillis(), familyCandle.getWindowEndMillis(), 
            familyId + " FAMILY", violations);
        
        // Validate consistency between family and equity window times
        if (familyCandle.getEquity() != null) {
            InstrumentCandle eq = familyCandle.getEquity();
            // For 1m candles, equity window should match family window
            if ("1m".equals(familyCandle.getTimeframe())) {
                if (eq.getWindowStartMillis() != familyCandle.getWindowStartMillis()) {
                    violations.add(String.format("%s: Equity window start (%d) != Family window start (%d)", 
                        familyId, eq.getWindowStartMillis(), familyCandle.getWindowStartMillis()));
                }
            }
        }
        
        // Validate spot-future premium (if both present)
        if (familyCandle.getEquity() != null && familyCandle.getFuture() != null) {
            double spot = familyCandle.getEquity().getClose();
            double future = familyCandle.getFuture().getClose();
            if (spot > 0) {
                double premium = Math.abs((future - spot) / spot * 100);
                if (premium > 10) {  // > 10% premium is suspicious
                    violations.add(String.format("%s: Suspicious spot-future premium: %.2f%% (spot=%.2f, future=%.2f)", 
                        familyId, premium, spot, future));
                }
            }
        }
        
        return violations;
    }

    // ==================== OPTION CANDLE VALIDATION ====================

    /**
     * Validate OptionCandle
     */
    public static List<String> validate(OptionCandle option) {
        List<String> violations = new ArrayList<>();
        
        if (option == null) {
            violations.add("OptionCandle is null");
            return violations;
        }
        
        String id = option.getScripCode() != null ? option.getScripCode() : "UNKNOWN";
        
        // OHLC validation
        validateOHLC(option.getOpen(), option.getHigh(), option.getLow(), option.getClose(), id, violations);
        
        // Strike price validation (primitive double, cannot be null)
        if (option.getStrikePrice() <= 0) {
            violations.add(String.format("%s: Invalid strike price: %.2f", id, option.getStrikePrice()));
        }
        
        // OI validation (primitive long, cannot be null)
        if (option.getOpenInterest() < 0) {
            violations.add(String.format("%s: Negative open interest: %d", id, option.getOpenInterest()));
        }
        
        return violations;
    }

    // ==================== CONVENIENCE METHODS ====================

    /**
     * Validate and log violations (convenience method)
     * @return true if valid, false if violations found
     */
    public static boolean validateAndLog(InstrumentCandle candle) {
        List<String> violations = validate(candle);
        if (!violations.isEmpty()) {
            log.error("🚨 InstrumentCandle validation failed: {}", violations);
            return false;
        }
        return true;
    }

    /**
     * Validate and log violations for FamilyCandle
     * @return true if valid, false if violations found
     */
    public static boolean validateAndLog(FamilyCandle candle) {
        List<String> violations = validate(candle);
        if (!violations.isEmpty()) {
            log.error("🚨 FamilyCandle validation failed: {}", violations);
            return false;
        }
        return true;
    }

    /**
     * Quick check if OHLC is valid (for inline use)
     */
    public static boolean isOHLCValid(double open, double high, double low, double close) {
        if (close <= 0) return false;
        if (high < low) return false;
        if (high < open || high < close) return false;
        if (low > open || low > close) return false;
        return true;
    }

    /**
     * Calculate range as percentage of close
     */
    public static double getRangePercent(InstrumentCandle candle) {
        if (candle == null || candle.getClose() <= 0) return 0;
        double range = candle.getHigh() - candle.getLow();
        return (range / candle.getClose()) * 100;
    }

    /**
     * Check if candle has suspicious tiny range for its timeframe
     */
    public static boolean hasTinyRange(InstrumentCandle candle, String timeframe) {
        double rangePercent = getRangePercent(candle);
        
        // Expected minimum ranges by timeframe
        double minRangePercent;
        switch (timeframe) {
            case "1m": minRangePercent = 0.01; break;
            case "5m": minRangePercent = 0.03; break;
            case "15m": minRangePercent = 0.05; break;
            case "30m": minRangePercent = 0.08; break;
            case "1h": minRangePercent = 0.1; break;
            case "2h": minRangePercent = 0.15; break;
            case "4h": minRangePercent = 0.2; break;
            case "1d": minRangePercent = 0.5; break;
            default: minRangePercent = 0.05;
        }
        
        return rangePercent < minRangePercent;
    }
}
