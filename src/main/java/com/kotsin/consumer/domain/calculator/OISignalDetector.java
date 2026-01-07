package com.kotsin.consumer.domain.calculator;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.domain.model.OptionCandle;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * OISignalDetector - Detects cross-instrument OI signals.
 * 
 * This is the KEY VALUE of the Family Candle architecture.
 * Analyzes OI changes across equity, future, and options to detect:
 * 
 * - BULLISH_ACCUMULATION: Price up + Future OI up + Call OI up + Put OI down
 * - BEARISH_DISTRIBUTION: Price down + Future OI up + Put OI up + Call OI down
 * - SHORT_COVERING_RALLY: Price up + Future OI down + Put OI down
 * - LONG_UNWINDING: Price down + Future OI down + Call OI down
 * 
 * Option Writing/Buying Detection (using OI change vs Price change):
 * - CALL_WRITING: Price down + Call OI up = Bearish (selling calls expecting further fall)
 * - CALL_BUYING: Price up + Call OI up = Bullish (buying calls expecting further rise)
 * - PUT_WRITING: Price up + Put OI up = Bullish (selling puts expecting further rise)
 * - PUT_BUYING: Price down + Put OI up = Bearish (buying puts expecting further fall)
 * 
 * Logic: When OI and price move in OPPOSITE directions = WRITING (selling)
 *        When OI and price move in SAME direction = BUYING (buying)
 * 
 * - NEUTRAL: No clear signal
 * 
 * This is what gives us the "deeper insight" the user requested.
 */
@Slf4j
public class OISignalDetector {

    // Thresholds
    private static final double PRICE_THRESHOLD = 0.1;  // 0.1% price change
    private static final double OI_THRESHOLD = 2.0;     // 2% OI change for futures
    private static final long OPTION_OI_MIN_CHANGE = 1000;  // Minimum OI change for options

    /**
     * OI Signal Type enumeration
     */
    public enum OISignalType {
        BULLISH_ACCUMULATION,   // Strong buy: price up, all OI confirms
        BEARISH_DISTRIBUTION,   // Strong sell: price down, all OI confirms
        SHORT_COVERING_RALLY,   // Temporary bullish: shorts exiting
        LONG_UNWINDING,         // Temporary bearish: longs exiting
        CALL_WRITING,           // Bearish: Price down + Call OI up = Selling calls
        CALL_BUYING,            // Bullish: Price up + Call OI up = Buying calls
        PUT_WRITING,            // Bullish: Price up + Put OI up = Selling puts
        PUT_BUYING,             // Bearish: Price down + Put OI up = Buying puts
        NEUTRAL                 // No clear signal
    }

    /**
     * Detect OI signal from FamilyCandle
     *
     * @param family FamilyCandle with equity, future, and options data
     * @return OISignalType classification
     */
    public static OISignalType detect(FamilyCandle family) {
        // FIX: Support both NSE (equity-based) and MCX (future-only) families
        if (family == null) {
            return OISignalType.NEUTRAL;
        }

        InstrumentCandle equity = family.getEquity();
        InstrumentCandle future = family.getFuture();
        List<OptionCandle> options = family.getOptions();

        // FIX: Use primary instrument (equity for NSE, future for MCX commodities)
        InstrumentCandle primary = equity != null ? equity : future;
        if (primary == null) {
            return OISignalType.NEUTRAL;
        }

        // Get price direction from primary instrument
        double priceChangePercent = primary.getPriceChangePercent();
        // FIX: Handle NaN/Infinity values
        if (Double.isNaN(priceChangePercent) || Double.isInfinite(priceChangePercent)) {
            return OISignalType.NEUTRAL;
        }
        
        boolean priceUp = priceChangePercent > PRICE_THRESHOLD;
        boolean priceDown = priceChangePercent < -PRICE_THRESHOLD;

        // Get future OI direction
        boolean futureOiUp = false;
        boolean futureOiDown = false;
        if (future != null && future.hasOI() && future.getOiChangePercent() != null) {
            futureOiUp = future.getOiChangePercent() > OI_THRESHOLD;
            futureOiDown = future.getOiChangePercent() < -OI_THRESHOLD;
        }

        // Get options OI direction
        long callOiChange = 0L;
        long putOiChange = 0L;
        if (options != null && !options.isEmpty()) {
            callOiChange = options.stream()
                .filter(OptionCandle::isCall)
                .mapToLong(OptionCandle::getOiChange)
                .sum();
            putOiChange = options.stream()
                .filter(OptionCandle::isPut)
                .mapToLong(OptionCandle::getOiChange)
                .sum();
        }

        boolean callOiUp = callOiChange > OPTION_OI_MIN_CHANGE;
        boolean callOiDown = callOiChange < -OPTION_OI_MIN_CHANGE;
        boolean putOiUp = putOiChange > OPTION_OI_MIN_CHANGE;
        boolean putOiDown = putOiChange < -OPTION_OI_MIN_CHANGE;

        // === THE KEY SIGNALS ===

        // BULLISH ACCUMULATION: Price up + Future OI up + Call OI up + Put OI down/neutral
        if (priceUp && futureOiUp && callOiUp && !putOiUp) {
            return OISignalType.BULLISH_ACCUMULATION;
        }

        // BEARISH DISTRIBUTION: Price down + Future OI up + Put OI up + Call OI down/neutral
        if (priceDown && futureOiUp && putOiUp && !callOiUp) {
            return OISignalType.BEARISH_DISTRIBUTION;
        }

        // SHORT COVERING RALLY: Price up + Future OI down + Put OI down
        if (priceUp && futureOiDown && putOiDown) {
            return OISignalType.SHORT_COVERING_RALLY;
        }

        // LONG UNWINDING: Price down + Future OI down + Call OI down
        if (priceDown && futureOiDown && callOiDown) {
            return OISignalType.LONG_UNWINDING;
        }

        // FIX: Use OI change vs Price change relationship to detect writing vs buying
        // When OI and price move in OPPOSITE directions = WRITING (selling)
        // When OI and price move in SAME direction = BUYING (buying)
        
        // CALL WRITING: Price down + Call OI up = Selling calls (bearish)
        // Logic: Price falling but call OI increasing = traders selling calls expecting further fall
        if (priceDown && callOiUp) {
            return OISignalType.CALL_WRITING;
        }

        // CALL BUYING: Price up + Call OI up = Buying calls (bullish)
        // Logic: Price rising and call OI increasing = traders buying calls expecting further rise
        if (priceUp && callOiUp) {
            return OISignalType.CALL_BUYING;
        }

        // PUT WRITING: Price up + Put OI up = Selling puts (bullish)
        // Logic: Price rising but put OI increasing = traders selling puts expecting further rise
        if (priceUp && putOiUp) {
            return OISignalType.PUT_WRITING;
        }

        // PUT BUYING: Price down + Put OI up = Buying puts (bearish)
        // Logic: Price falling and put OI increasing = traders buying puts expecting further fall
        if (priceDown && putOiUp) {
            return OISignalType.PUT_BUYING;
        }

        return OISignalType.NEUTRAL;
    }

    /**
     * Check if signal is bullish
     */
    public static boolean isBullish(OISignalType signal) {
        return signal == OISignalType.BULLISH_ACCUMULATION 
            || signal == OISignalType.SHORT_COVERING_RALLY
            || signal == OISignalType.PUT_WRITING
            || signal == OISignalType.CALL_BUYING;
    }

    /**
     * Check if signal is bearish
     */
    public static boolean isBearish(OISignalType signal) {
        return signal == OISignalType.BEARISH_DISTRIBUTION 
            || signal == OISignalType.LONG_UNWINDING
            || signal == OISignalType.CALL_WRITING
            || signal == OISignalType.PUT_BUYING;
    }

    /**
     * Check if signal is strong (new positions being created)
     */
    public static boolean isStrong(OISignalType signal) {
        return signal == OISignalType.BULLISH_ACCUMULATION 
            || signal == OISignalType.BEARISH_DISTRIBUTION;
    }

    /**
     * Get signal confidence score
     *
     * @param family FamilyCandle
     * @param signal Detected signal
     * @return Confidence 0.0 to 1.0
     */
    public static double getConfidence(FamilyCandle family, OISignalType signal) {
        if (signal == OISignalType.NEUTRAL) {
            return 0.0;
        }

        double confidence = 0.5;  // Base confidence

        // Higher confidence if we have all data
        if (family.isHasFuture()) {
            confidence += 0.2;
        }
        if (family.isHasOptions()) {
            confidence += 0.2;
        }

        // Higher confidence for strong signals
        if (isStrong(signal)) {
            confidence += 0.1;
        }

        return Math.min(1.0, confidence);
    }

    /**
     * Get directional bias from signal
     *
     * @param signal OISignalType
     * @return 1 for bullish, -1 for bearish, 0 for neutral
     */
    public static int getDirectionalBias(OISignalType signal) {
        if (isBullish(signal)) return 1;
        if (isBearish(signal)) return -1;
        return 0;
    }

    /**
     * Describe signal in human-readable format
     *
     * @param signal OISignalType
     * @return Description string
     */
    public static String describe(OISignalType signal) {
        switch (signal) {
            case BULLISH_ACCUMULATION:
                return "Strong bullish: New longs being built, smart money bullish";
            case BEARISH_DISTRIBUTION:
                return "Strong bearish: New shorts being built, smart money bearish";
            case SHORT_COVERING_RALLY:
                return "Short-term bullish: Shorts exiting, potential rally";
            case LONG_UNWINDING:
                return "Short-term bearish: Longs exiting, potential decline";
            case CALL_WRITING:
                return "Bearish: Price down + Call OI up = Selling calls (expecting further fall)";
            case CALL_BUYING:
                return "Bullish: Price up + Call OI up = Buying calls (expecting further rise)";
            case PUT_WRITING:
                return "Bullish: Price up + Put OI up = Selling puts (expecting further rise)";
            case PUT_BUYING:
                return "Bearish: Price down + Put OI up = Buying puts (expecting further fall)";
            default:
                return "No clear signal from OI analysis";
        }
    }
}
