package com.kotsin.consumer.enrichment.calculator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * StatisticsCalculator - Core statistics calculations for SMTIS
 *
 * Provides:
 * - Mean (average)
 * - Standard Deviation
 * - Z-Score (how many stddev from mean)
 * - Percentile (rank within history)
 *
 * These replace hardcoded thresholds with adaptive, instrument-relative thresholds.
 *
 * Example:
 * - NATURALGAS OI base = 4000, OI change = 200 (5%) -> percentile 95 -> SIGNAL
 * - NIFTY OI base = 10M, OI change = 1000 (0.01%) -> percentile 30 -> NO SIGNAL
 *
 * Same absolute value, different percentiles, different outcomes.
 */
@Slf4j
@Component
public class StatisticsCalculator {

    /**
     * Calculate mean (average) of values
     *
     * @param values List of historical values
     * @return Mean value, or 0 if empty
     */
    public double calculateMean(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }

        double sum = 0.0;
        for (Double v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    /**
     * Calculate standard deviation
     *
     * Uses population standard deviation (N, not N-1)
     * since we want consistency with the exact history window.
     *
     * @param values List of historical values
     * @return Standard deviation, minimum 0.0001 to avoid division by zero
     */
    public double calculateStdDev(List<Double> values) {
        if (values == null || values.size() < 2) {
            return 0.0001; // Minimum to avoid division by zero
        }

        double mean = calculateMean(values);
        double sumSquaredDiff = 0.0;

        for (Double v : values) {
            double diff = v - mean;
            sumSquaredDiff += diff * diff;
        }

        double variance = sumSquaredDiff / values.size();
        double stddev = Math.sqrt(variance);

        // Minimum stddev to avoid division by zero in z-score
        return Math.max(stddev, 0.0001);
    }

    /**
     * Calculate Z-Score: How many standard deviations from mean
     *
     * Formula: z = (value - mean) / stddev
     *
     * Interpretation:
     * - z > 2.0: Very high (>97.7% of values)
     * - z > 1.0: High (>84% of values)
     * - z = 0: Average
     * - z < -1.0: Low (<16% of values)
     * - z < -2.0: Very low (<2.3% of values)
     *
     * @param value Current value
     * @param mean Historical mean
     * @param stddev Historical standard deviation
     * @return Z-score
     */
    public double calculateZScore(double value, double mean, double stddev) {
        if (stddev < 0.0001) {
            return 0.0;
        }
        return (value - mean) / stddev;
    }

    /**
     * Calculate Z-Score from list (convenience method)
     */
    public double calculateZScore(double value, List<Double> values) {
        double mean = calculateMean(values);
        double stddev = calculateStdDev(values);
        return calculateZScore(value, mean, stddev);
    }

    /**
     * Calculate Percentile: Where value ranks in history
     *
     * Uses simple rank percentile: (count of values < current) / total * 100
     *
     * Interpretation:
     * - 95th percentile: Higher than 95% of historical values
     * - 50th percentile: Median
     * - 5th percentile: Lower than 95% of historical values
     *
     * @param value Current value
     * @param values Historical values
     * @return Percentile [0, 100], or 50 if insufficient data
     */
    public double calculatePercentile(double value, List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 50.0; // Neutral percentile when no data
        }

        long countLessThan = values.stream()
                .filter(v -> v < value)
                .count();

        return (countLessThan / (double) values.size()) * 100.0;
    }

    /**
     * Calculate percentile rank using sorted position
     *
     * More accurate for larger datasets, handles ties better.
     *
     * @param value Current value
     * @param values Historical values (will be sorted internally)
     * @return Percentile [0, 100]
     */
    public double calculatePercentileRank(double value, List<Double> sortedValues) {
        if (sortedValues == null || sortedValues.isEmpty()) {
            return 50.0;
        }

        // Binary search to find position
        int insertionPoint = Collections.binarySearch(sortedValues, value);

        if (insertionPoint < 0) {
            // Value not found, convert to insertion point
            insertionPoint = -(insertionPoint + 1);
        }

        return (insertionPoint / (double) sortedValues.size()) * 100.0;
    }

    /**
     * Calculate all statistics at once (more efficient for repeated calculations)
     */
    public Statistics calculateAll(double currentValue, List<Double> history) {
        if (history == null || history.size() < 2) {
            return Statistics.insufficient(currentValue);
        }

        double mean = calculateMean(history);
        double stddev = calculateStdDev(history);
        double zscore = calculateZScore(currentValue, mean, stddev);
        double percentile = calculatePercentile(currentValue, history);

        return new Statistics(currentValue, mean, stddev, zscore, percentile, true);
    }

    /**
     * Container for all statistics
     */
    public record Statistics(
            double value,
            double mean,
            double stddev,
            double zscore,
            double percentile,
            boolean valid
    ) {
        public static Statistics insufficient(double value) {
            return new Statistics(value, 0, 0, 0, 50, false);
        }

        public boolean isExtreme() {
            return Math.abs(zscore) > 2.0;
        }

        public boolean isHigh() {
            return percentile > 80.0;
        }

        public boolean isLow() {
            return percentile < 20.0;
        }
    }

    /**
     * Calculate consecutive count in same direction
     *
     * @param values Recent values (most recent last)
     * @param positive True if counting positive values, false for negative
     * @return Count of consecutive values in the specified direction
     */
    public int calculateConsecutiveCount(List<Double> values, boolean positive) {
        if (values == null || values.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (int i = values.size() - 1; i >= 0; i--) {
            double v = values.get(i);
            if ((positive && v > 0) || (!positive && v < 0)) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    /**
     * Detect velocity/acceleration of metric
     *
     * @param values Recent values (most recent last)
     * @return Velocity (rate of change), positive = accelerating up
     */
    public double calculateVelocity(List<Double> values) {
        if (values == null || values.size() < 3) {
            return 0.0;
        }

        // Use simple linear regression slope
        int n = Math.min(5, values.size());
        List<Double> recent = values.subList(values.size() - n, values.size());

        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += recent.get(i);
            sumXY += i * recent.get(i);
            sumX2 += i * i;
        }

        double denominator = n * sumX2 - sumX * sumX;
        if (Math.abs(denominator) < 0.0001) {
            return 0.0;
        }

        return (n * sumXY - sumX * sumY) / denominator;
    }
}
