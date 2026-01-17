package com.kotsin.consumer.util;

/**
 * MathUtils - Safe mathematical operations with NaN/Infinity/Division-by-zero protection
 *
 * BUGS FIXED:
 * - Bug #41: VWAP division by zero
 * - Bug #44: Z-score with zero stddev
 * - Bug #46: Floating point comparison issues
 * - Bug #47: Integer overflow in volume calculation
 * - Bug #48: PCR calculation when put OI = 0
 * - Bug #50: Log returns vs simple returns mismatch
 * - Bug #84: OFI velocity undefined if previous = 0
 *
 * USAGE:
 * Instead of: double result = a / b;
 * Use: double result = MathUtils.safeDivide(a, b, 0.0);
 */
public final class MathUtils {

    private MathUtils() {} // Prevent instantiation

    // Epsilon for floating point comparisons
    private static final double EPSILON = 1e-10;

    // ======================== SAFE DIVISION ========================

    /**
     * Safe division that returns defaultValue if denominator is 0, NaN, or Infinity
     *
     * @param numerator   The numerator
     * @param denominator The denominator
     * @param defaultValue Value to return if division is unsafe
     * @return Result of division or defaultValue
     */
    public static double safeDivide(double numerator, double denominator, double defaultValue) {
        if (!isValidDenominator(denominator)) {
            return defaultValue;
        }
        double result = numerator / denominator;
        if (!isValidNumber(result)) {
            return defaultValue;
        }
        return result;
    }

    /**
     * Safe division for longs - prevents overflow
     */
    public static double safeDivide(long numerator, long denominator, double defaultValue) {
        if (denominator == 0) {
            return defaultValue;
        }
        return (double) numerator / denominator;
    }

    /**
     * Check if a number is valid for use as a denominator
     */
    public static boolean isValidDenominator(double value) {
        return value != 0 && !Double.isNaN(value) && !Double.isInfinite(value);
    }

    // ======================== NUMBER VALIDATION ========================

    /**
     * Check if a number is valid (not NaN, not Infinite)
     */
    public static boolean isValidNumber(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    /**
     * Check if a number is valid and positive
     */
    public static boolean isValidPositive(double value) {
        return isValidNumber(value) && value > 0;
    }

    /**
     * Check if a Double wrapper is valid (not null, not NaN, not Infinite)
     */
    public static boolean isValidNumber(Double value) {
        return value != null && !Double.isNaN(value) && !Double.isInfinite(value);
    }

    /**
     * Get value or default if invalid
     */
    public static double valueOrDefault(Double value, double defaultValue) {
        return isValidNumber(value) ? value : defaultValue;
    }

    // ======================== PERCENTAGE CALCULATIONS ========================

    /**
     * Safe percentage calculation: (value / total) * 100
     */
    public static double safePercentage(double value, double total, double defaultValue) {
        return safeDivide(value, total, defaultValue / 100.0) * 100.0;
    }

    /**
     * Safe percentage change: ((new - old) / old) * 100
     * Bug #50: This is SIMPLE returns, not log returns
     */
    public static double safePercentageChange(double newValue, double oldValue, double defaultValue) {
        if (!isValidDenominator(oldValue)) {
            return defaultValue;
        }
        double change = (newValue - oldValue) / oldValue * 100.0;
        return isValidNumber(change) ? change : defaultValue;
    }

    /**
     * Safe log returns: ln(new / old)
     * Bug #50: Use this for compounding, use safePercentageChange for simple returns
     */
    public static double safeLogReturn(double newValue, double oldValue, double defaultValue) {
        if (!isValidPositive(newValue) || !isValidPositive(oldValue)) {
            return defaultValue;
        }
        double result = Math.log(newValue / oldValue);
        return isValidNumber(result) ? result : defaultValue;
    }

    // ======================== RATE OF CHANGE (ROC) ========================

    /**
     * Safe rate of change: (current - previous) / |previous|
     * Bug #84: Returns defaultValue if previous is 0
     */
    public static double safeROC(double current, double previous, double defaultValue) {
        if (Math.abs(previous) < EPSILON) {
            return defaultValue;
        }
        return (current - previous) / Math.abs(previous);
    }

    // ======================== Z-SCORE ========================

    /**
     * Safe z-score calculation: (value - mean) / stddev
     * Bug #44: Returns defaultValue if stddev is 0
     */
    public static double safeZScore(double value, double mean, double stddev, double defaultValue) {
        if (!isValidDenominator(stddev) || Math.abs(stddev) < EPSILON) {
            return defaultValue;
        }
        double zscore = (value - mean) / stddev;
        return isValidNumber(zscore) ? zscore : defaultValue;
    }

    // ======================== RATIO CALCULATIONS ========================

    /**
     * Safe Put/Call Ratio calculation
     * Bug #48: Returns defaultValue if putOI is 0 or too small
     */
    public static double safePCR(long callOI, long putOI, double defaultValue) {
        if (putOI <= 0) {
            return defaultValue;
        }
        double pcr = (double) callOI / putOI;
        // PCR > 10 or < 0.1 is likely data error
        if (pcr > 10 || pcr < 0.1) {
            return defaultValue;
        }
        return pcr;
    }

    // ======================== FLOATING POINT COMPARISON ========================

    /**
     * Safe floating point equality comparison
     * Bug #46: Never use == for doubles
     */
    public static boolean equals(double a, double b) {
        return Math.abs(a - b) < EPSILON;
    }

    /**
     * Safe floating point comparison with custom epsilon
     */
    public static boolean equals(double a, double b, double epsilon) {
        return Math.abs(a - b) < epsilon;
    }

    /**
     * Check if a <= b with epsilon tolerance
     */
    public static boolean lessThanOrEqual(double a, double b) {
        return a < b || equals(a, b);
    }

    /**
     * Check if a >= b with epsilon tolerance
     */
    public static boolean greaterThanOrEqual(double a, double b) {
        return a > b || equals(a, b);
    }

    // ======================== CLAMPING ========================

    /**
     * Clamp value to range [min, max] with NaN protection
     */
    public static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return (min + max) / 2; // Return midpoint for NaN
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? max : min;
        }
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Clamp percentage to [0, 100]
     */
    public static double clampPercentage(double value) {
        return clamp(value, 0.0, 100.0);
    }

    /**
     * Clamp confidence to [0, 1]
     */
    public static double clampConfidence(double value) {
        return clamp(value, 0.0, 1.0);
    }

    // ======================== OVERFLOW PROTECTION ========================

    /**
     * Safe addition for longs - prevents overflow
     * Bug #47: Integer overflow in volume calculation
     */
    public static long safeAdd(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            return a > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    /**
     * Safe multiplication for longs - prevents overflow
     */
    public static long safeMultiply(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            return (a > 0) == (b > 0) ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    // ======================== VWAP SPECIFIC ========================

    /**
     * Safe VWAP calculation: totalValue / totalVolume
     * Bug #41: Returns defaultValue if totalVolume is 0
     */
    public static double safeVWAP(double totalValue, double totalVolume, double defaultClose) {
        if (totalVolume <= 0) {
            return defaultClose;
        }
        double vwap = totalValue / totalVolume;
        return isValidNumber(vwap) ? vwap : defaultClose;
    }

    // ======================== STATISTICAL ========================

    /**
     * Calculate standard deviation safely
     */
    public static double safeStdDev(double[] values) {
        if (values == null || values.length < 2) {
            return 0.0;
        }

        double sum = 0.0;
        int count = 0;
        for (double v : values) {
            if (isValidNumber(v)) {
                sum += v;
                count++;
            }
        }

        if (count < 2) {
            return 0.0;
        }

        double mean = sum / count;
        double sumSquaredDiff = 0.0;
        for (double v : values) {
            if (isValidNumber(v)) {
                sumSquaredDiff += Math.pow(v - mean, 2);
            }
        }

        return Math.sqrt(sumSquaredDiff / (count - 1));
    }

    /**
     * Calculate percentile from array (0-100)
     */
    public static double percentile(double[] sortedValues, double percentile) {
        if (sortedValues == null || sortedValues.length == 0) {
            return 0.0;
        }
        if (percentile <= 0) return sortedValues[0];
        if (percentile >= 100) return sortedValues[sortedValues.length - 1];

        double idx = percentile / 100.0 * (sortedValues.length - 1);
        int lower = (int) Math.floor(idx);
        int upper = (int) Math.ceil(idx);

        if (lower == upper) {
            return sortedValues[lower];
        }

        double weight = idx - lower;
        return sortedValues[lower] * (1 - weight) + sortedValues[upper] * weight;
    }
}
