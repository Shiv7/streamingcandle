package com.kotsin.consumer.curated.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MultiTimeframeLevels - Fibonacci and Pivot levels across multiple timeframes
 * Provides key support/resistance levels for entry/target calculation
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiTimeframeLevels {

    private String scripCode;
    private double currentPrice;
    private long timestamp;

    // Daily levels
    private FibonacciLevels dailyFib;
    private PivotLevels dailyPivot;

    // Weekly levels
    private FibonacciLevels weeklyFib;
    private PivotLevels weeklyPivot;

    // Monthly levels
    private FibonacciLevels monthlyFib;
    private PivotLevels monthlyPivot;

    /**
     * Fibonacci retracement levels
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FibonacciLevels {
        private String timeframe;       // "daily", "weekly", "monthly"
        private double swingHigh;
        private double swingLow;
        private double range;

        // Retracement levels (from high to low)
        private double fib236;          // 23.6% retracement
        private double fib382;          // 38.2% retracement
        private double fib50;           // 50% retracement
        private double fib618;          // 61.8% retracement (Golden ratio)
        private double fib786;          // 78.6% retracement

        // Extension levels (beyond high)
        private double fib1272;         // 127.2% extension
        private double fib1618;         // 161.8% extension (Golden ratio)
        private double fib200;          // 200% extension
    }

    /**
     * Pivot points (Classic method)
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PivotLevels {
        private String timeframe;       // "daily", "weekly", "monthly"
        private double high;
        private double low;
        private double close;

        // Pivot point
        private double pivot;           // (H + L + C) / 3

        // Resistance levels
        private double r1;              // 2*P - L
        private double r2;              // P + (H - L)
        private double r3;              // H + 2*(P - L)
        private double r4;              // H + 3*(P - L)

        // Support levels
        private double s1;              // 2*P - H
        private double s2;              // P - (H - L)
        private double s3;              // L - 2*(H - P)
        private double s4;              // L - 3*(H - P)

        // CPR (Central Pivot Range) - Frank Ochoa's Standard Formula
        // TC = 2*PP - BC (where PP = (H+L+C)/3)
        // BC = (H+L)/2
        // When close is near (H+L)/2, CPR is narrow = breakout expected
        private double tc;              // Top Central: 2*PP - BC
        private double bc;              // Bottom Central: (H + L) / 2
        private double cprWidth;        // |TC - BC| / PP * 100 (percentage)

        // CPR classification
        private CPRWidth cprType;

        public enum CPRWidth {
            NARROW,     // < 0.3% - expect breakout
            NORMAL,     // 0.3% - 0.7%
            WIDE        // > 0.7% - range-bound
        }
    }

    /**
     * Get nearest support level from all timeframes
     */
    public double getNearestSupport(double currentPrice) {
        double nearest = 0;
        double minDistance = Double.MAX_VALUE;

        // Check all support levels
        for (PivotLevels pivot : new PivotLevels[]{dailyPivot, weeklyPivot, monthlyPivot}) {
            if (pivot == null) continue;

            double[] supports = {pivot.s1, pivot.s2, pivot.s3, pivot.pivot};
            for (double support : supports) {
                if (support < currentPrice) {
                    double distance = currentPrice - support;
                    if (distance < minDistance) {
                        minDistance = distance;
                        nearest = support;
                    }
                }
            }
        }

        // Also check Fibonacci supports
        for (FibonacciLevels fib : new FibonacciLevels[]{dailyFib, weeklyFib, monthlyFib}) {
            if (fib == null) continue;

            double[] fibs = {fib.fib236, fib.fib382, fib.fib50, fib.fib618, fib.fib786};
            for (double fibLevel : fibs) {
                if (fibLevel < currentPrice) {
                    double distance = currentPrice - fibLevel;
                    if (distance < minDistance) {
                        minDistance = distance;
                        nearest = fibLevel;
                    }
                }
            }
        }

        return nearest;
    }

    /**
     * Get nearest resistance level from all timeframes
     */
    public double getNearestResistance(double currentPrice) {
        double nearest = Double.MAX_VALUE;
        double minDistance = Double.MAX_VALUE;

        // Check all resistance levels
        for (PivotLevels pivot : new PivotLevels[]{dailyPivot, weeklyPivot, monthlyPivot}) {
            if (pivot == null) continue;

            double[] resistances = {pivot.r1, pivot.r2, pivot.r3, pivot.pivot};
            for (double resistance : resistances) {
                if (resistance > currentPrice) {
                    double distance = resistance - currentPrice;
                    if (distance < minDistance) {
                        minDistance = distance;
                        nearest = resistance;
                    }
                }
            }
        }

        // Also check Fibonacci resistances
        for (FibonacciLevels fib : new FibonacciLevels[]{dailyFib, weeklyFib, monthlyFib}) {
            if (fib == null) continue;

            double[] fibs = {fib.fib236, fib.fib382, fib.fib50, fib.fib618, fib.fib786,
                    fib.fib1272, fib.fib1618, fib.fib200};
            for (double fibLevel : fibs) {
                if (fibLevel > currentPrice) {
                    double distance = fibLevel - currentPrice;
                    if (distance < minDistance) {
                        minDistance = distance;
                        nearest = fibLevel;
                    }
                }
            }
        }

        return nearest == Double.MAX_VALUE ? 0 : nearest;
    }

    /**
     * Check if current price is near a significant level (within 0.5%)
     */
    public boolean isNearSignificantLevel(double currentPrice) {
        double tolerance = currentPrice * 0.005;  // 0.5%

        // Check pivots
        for (PivotLevels pivot : new PivotLevels[]{dailyPivot, weeklyPivot, monthlyPivot}) {
            if (pivot == null) continue;

            double[] levels = {pivot.pivot, pivot.r1, pivot.r2, pivot.s1, pivot.s2};
            for (double level : levels) {
                if (Math.abs(currentPrice - level) <= tolerance) {
                    return true;
                }
            }
        }

        // Check Fibonacci
        for (FibonacciLevels fib : new FibonacciLevels[]{dailyFib, weeklyFib, monthlyFib}) {
            if (fib == null) continue;

            double[] levels = {fib.fib382, fib.fib50, fib.fib618};
            for (double level : levels) {
                if (Math.abs(currentPrice - level) <= tolerance) {
                    return true;
                }
            }
        }

        return false;
    }
}
