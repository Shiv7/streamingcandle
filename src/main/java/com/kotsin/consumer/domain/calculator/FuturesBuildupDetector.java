package com.kotsin.consumer.domain.calculator;

import com.kotsin.consumer.domain.model.InstrumentCandle;
import lombok.extern.slf4j.Slf4j;

/**
 * FuturesBuildupDetector - Detects futures buildup patterns from price and OI changes.
 * 
 * Buildup Classification:
 * - LONG_BUILDUP:     Price ↑ + OI ↑ = New long positions, bullish
 * - SHORT_BUILDUP:    Price ↓ + OI ↑ = New short positions, bearish
 * - LONG_UNWINDING:   Price ↓ + OI ↓ = Longs exiting, bearish
 * - SHORT_COVERING:   Price ↑ + OI ↓ = Shorts exiting, bullish (temporary)
 * - NEUTRAL:          No clear signal
 * 
 * This is a crucial cross-instrument signal when combined with equity movement.
 */
@Slf4j
public class FuturesBuildupDetector {

    // Threshold for significant changes
    private static final double PRICE_CHANGE_THRESHOLD = 0.1;  // 0.1% price change
    private static final double OI_CHANGE_THRESHOLD = 1.0;     // 1% OI change

    /**
     * Buildup Type enumeration
     */
    public enum BuildupType {
        LONG_BUILDUP,      // Price up + OI up = Strong bullish
        SHORT_BUILDUP,     // Price down + OI up = Strong bearish  
        LONG_UNWINDING,    // Price down + OI down = Weak bearish (longs exiting)
        SHORT_COVERING,    // Price up + OI down = Weak bullish (shorts exiting)
        NEUTRAL            // No clear signal
    }

    /**
     * Detect buildup type from future candle
     *
     * @param futureCandle Future instrument candle with OI data
     * @return BuildupType classification
     */
    public static BuildupType detect(InstrumentCandle futureCandle) {
        if (futureCandle == null || !futureCandle.hasOI()) {
            return BuildupType.NEUTRAL;
        }

        double priceChange = futureCandle.getPriceChangePercent();
        Double oiChangePercent = futureCandle.getOiChangePercent();

        if (oiChangePercent == null) {
            return BuildupType.NEUTRAL;
        }

        return detect(priceChange, oiChangePercent);
    }

    /**
     * Detect buildup type from price and OI change percentages
     *
     * @param priceChangePercent Price change in percentage
     * @param oiChangePercent OI change in percentage
     * @return BuildupType classification
     */
    public static BuildupType detect(double priceChangePercent, double oiChangePercent) {
        boolean priceUp = priceChangePercent > PRICE_CHANGE_THRESHOLD;
        boolean priceDown = priceChangePercent < -PRICE_CHANGE_THRESHOLD;
        boolean oiUp = oiChangePercent > OI_CHANGE_THRESHOLD;
        boolean oiDown = oiChangePercent < -OI_CHANGE_THRESHOLD;

        if (priceUp && oiUp) {
            return BuildupType.LONG_BUILDUP;
        } else if (priceDown && oiUp) {
            return BuildupType.SHORT_BUILDUP;
        } else if (priceDown && oiDown) {
            return BuildupType.LONG_UNWINDING;
        } else if (priceUp && oiDown) {
            return BuildupType.SHORT_COVERING;
        } else {
            return BuildupType.NEUTRAL;
        }
    }

    /**
     * Detect buildup type with custom thresholds
     *
     * @param priceChangePercent Price change percentage
     * @param oiChangePercent OI change percentage
     * @param priceThreshold Custom price threshold
     * @param oiThreshold Custom OI threshold
     * @return BuildupType classification
     */
    public static BuildupType detectWithThresholds(
        double priceChangePercent, 
        double oiChangePercent,
        double priceThreshold,
        double oiThreshold
    ) {
        boolean priceUp = priceChangePercent > priceThreshold;
        boolean priceDown = priceChangePercent < -priceThreshold;
        boolean oiUp = oiChangePercent > oiThreshold;
        boolean oiDown = oiChangePercent < -oiThreshold;

        if (priceUp && oiUp) {
            return BuildupType.LONG_BUILDUP;
        } else if (priceDown && oiUp) {
            return BuildupType.SHORT_BUILDUP;
        } else if (priceDown && oiDown) {
            return BuildupType.LONG_UNWINDING;
        } else if (priceUp && oiDown) {
            return BuildupType.SHORT_COVERING;
        } else {
            return BuildupType.NEUTRAL;
        }
    }

    /**
     * Check if buildup type is bullish
     *
     * @param type BuildupType
     * @return True if bullish signal
     */
    public static boolean isBullish(BuildupType type) {
        return type == BuildupType.LONG_BUILDUP || type == BuildupType.SHORT_COVERING;
    }

    /**
     * Check if buildup type is bearish
     *
     * @param type BuildupType
     * @return True if bearish signal
     */
    public static boolean isBearish(BuildupType type) {
        return type == BuildupType.SHORT_BUILDUP || type == BuildupType.LONG_UNWINDING;
    }

    /**
     * Check if buildup type is strong (new positions being created)
     *
     * @param type BuildupType
     * @return True if strong signal (LONG_BUILDUP or SHORT_BUILDUP)
     */
    public static boolean isStrong(BuildupType type) {
        return type == BuildupType.LONG_BUILDUP || type == BuildupType.SHORT_BUILDUP;
    }

    /**
     * Check if buildup type is weak (positions being closed)
     *
     * @param type BuildupType
     * @return True if weak signal (UNWINDING or COVERING)
     */
    public static boolean isWeak(BuildupType type) {
        return type == BuildupType.LONG_UNWINDING || type == BuildupType.SHORT_COVERING;
    }

    /**
     * Get directional bias from buildup type
     *
     * @param type BuildupType
     * @return 1 for bullish, -1 for bearish, 0 for neutral
     */
    public static int getDirectionalBias(BuildupType type) {
        switch (type) {
            case LONG_BUILDUP:
                return 1;
            case SHORT_COVERING:
                return 1;  // Temporary bullish
            case SHORT_BUILDUP:
                return -1;
            case LONG_UNWINDING:
                return -1; // Temporary bearish
            default:
                return 0;
        }
    }

    /**
     * Get confidence level for buildup signal
     *
     * @param type BuildupType
     * @param oiChangePercent Magnitude of OI change
     * @return Confidence 0.0 to 1.0
     */
    public static double getConfidence(BuildupType type, double oiChangePercent) {
        if (type == BuildupType.NEUTRAL) {
            return 0.0;
        }

        double absChange = Math.abs(oiChangePercent);
        
        // Higher OI change = higher confidence
        if (absChange >= 5.0) {
            return isStrong(type) ? 1.0 : 0.8;
        } else if (absChange >= 3.0) {
            return isStrong(type) ? 0.8 : 0.6;
        } else if (absChange >= 1.0) {
            return isStrong(type) ? 0.6 : 0.4;
        } else {
            return 0.2;
        }
    }
}
