package com.kotsin.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * PivotLevels - Contains all pivot point calculations for a given period.
 *
 * Includes:
 * - Standard Pivots (S1-S4, R1-R4)
 * - Fibonacci Pivots
 * - Camarilla Pivots
 * - CPR (Central Pivot Range - TC/BC)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PivotLevels implements Serializable {

    private static final long serialVersionUID = 1L;

    // Standard Pivot Points
    private double pivot;
    private double s1;
    private double s2;
    private double s3;
    private double s4;
    private double r1;
    private double r2;
    private double r3;
    private double r4;

    // Fibonacci Pivot Points
    private double fibS1;
    private double fibS2;
    private double fibS3;
    private double fibR1;
    private double fibR2;
    private double fibR3;

    // Camarilla Pivot Points
    private double camS1;
    private double camS2;
    private double camS3;
    private double camS4;
    private double camR1;
    private double camR2;
    private double camR3;
    private double camR4;

    // Central Pivot Range (CPR)
    private double tc;  // Top Central
    private double bc;  // Bottom Central

    /**
     * Get CPR width (absolute).
     */
    public double getCprWidth() {
        return Math.abs(tc - bc);
    }

    /**
     * Get CPR width as percentage of pivot.
     */
    public double getCprWidthPercent() {
        if (pivot == 0) return 0;
        return (getCprWidth() / pivot) * 100;
    }

    /**
     * Get CPR width as percentage of given price.
     */
    public double getCprWidthPercent(double price) {
        if (price == 0) return 0;
        return (getCprWidth() / price) * 100;
    }

    /**
     * Check if CPR is thin (high breakout probability).
     */
    public boolean isThinCpr() {
        return getCprWidthPercent() < 0.5;
    }

    /**
     * Check if CPR is ultra-thin (very high breakout probability).
     */
    public boolean isUltraThinCpr() {
        return getCprWidthPercent() < 0.3;
    }

    /**
     * Check if price is above CPR.
     */
    public boolean isPriceAboveCpr(double price) {
        return price > tc;
    }

    /**
     * Check if price is below CPR.
     */
    public boolean isPriceBelowCpr(double price) {
        return price < bc;
    }

    /**
     * Check if price is inside CPR.
     */
    public boolean isPriceInsideCpr(double price) {
        return price >= bc && price <= tc;
    }

    /**
     * Get nearest support level below price.
     */
    public double getNearestSupport(double price) {
        double[] supports = {s1, s2, s3, s4, bc, fibS1, fibS2, fibS3, camS1, camS2, camS3, camS4};
        double nearest = 0;
        double minDistance = Double.MAX_VALUE;

        for (double support : supports) {
            if (support > 0 && support < price) {
                double distance = price - support;
                if (distance < minDistance) {
                    minDistance = distance;
                    nearest = support;
                }
            }
        }
        return nearest;
    }

    /**
     * Get nearest resistance level above price.
     */
    public double getNearestResistance(double price) {
        double[] resistances = {r1, r2, r3, r4, tc, fibR1, fibR2, fibR3, camR1, camR2, camR3, camR4};
        double nearest = 0;
        double minDistance = Double.MAX_VALUE;

        for (double resistance : resistances) {
            if (resistance > 0 && resistance > price) {
                double distance = resistance - price;
                if (distance < minDistance) {
                    minDistance = distance;
                    nearest = resistance;
                }
            }
        }
        return nearest;
    }

    /**
     * Check if a price level is near this pivot's levels (within threshold).
     */
    public boolean isNearAnyLevel(double price, double thresholdPercent) {
        double threshold = price * thresholdPercent / 100;

        double[] allLevels = {
            pivot, s1, s2, s3, s4, r1, r2, r3, r4,
            tc, bc,
            fibS1, fibS2, fibS3, fibR1, fibR2, fibR3,
            camS1, camS2, camS3, camS4, camR1, camR2, camR3, camR4
        };

        for (double level : allLevels) {
            if (level > 0 && Math.abs(price - level) <= threshold) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the level name that price is nearest to.
     */
    public String getNearestLevelName(double price, double thresholdPercent) {
        double threshold = price * thresholdPercent / 100;
        double minDistance = Double.MAX_VALUE;
        String nearestName = null;

        // Check all levels
        if (Math.abs(price - pivot) <= threshold && Math.abs(price - pivot) < minDistance) {
            minDistance = Math.abs(price - pivot);
            nearestName = "Pivot";
        }
        if (Math.abs(price - s1) <= threshold && Math.abs(price - s1) < minDistance) {
            minDistance = Math.abs(price - s1);
            nearestName = "S1";
        }
        if (Math.abs(price - s2) <= threshold && Math.abs(price - s2) < minDistance) {
            minDistance = Math.abs(price - s2);
            nearestName = "S2";
        }
        if (Math.abs(price - r1) <= threshold && Math.abs(price - r1) < minDistance) {
            minDistance = Math.abs(price - r1);
            nearestName = "R1";
        }
        if (Math.abs(price - r2) <= threshold && Math.abs(price - r2) < minDistance) {
            minDistance = Math.abs(price - r2);
            nearestName = "R2";
        }
        if (Math.abs(price - tc) <= threshold && Math.abs(price - tc) < minDistance) {
            minDistance = Math.abs(price - tc);
            nearestName = "TC";
        }
        if (Math.abs(price - bc) <= threshold && Math.abs(price - bc) < minDistance) {
            minDistance = Math.abs(price - bc);
            nearestName = "BC";
        }
        if (Math.abs(price - camS3) <= threshold && Math.abs(price - camS3) < minDistance) {
            minDistance = Math.abs(price - camS3);
            nearestName = "CamS3";
        }
        if (Math.abs(price - camR3) <= threshold && Math.abs(price - camR3) < minDistance) {
            minDistance = Math.abs(price - camR3);
            nearestName = "CamR3";
        }

        return nearestName;
    }
}
