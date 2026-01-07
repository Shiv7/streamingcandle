package com.kotsin.consumer.domain.model;

import java.util.Objects;

/**
 * Immutable value object for Volume Profile analysis.
 *
 * Volume Profile shows the distribution of volume across different price levels.
 * Key metrics:
 * - POC (Point of Control): Price level with highest volume
 * - Value Area: Range containing 70% of volume around POC
 * - VPIN: Volume-Synchronized Probability of Informed Trading
 */
public final class VolumeProfile {
    private final Double poc;              // Point of Control (price with max volume)
    private final Double valueAreaHigh;    // Upper bound of 70% volume area
    private final Double valueAreaLow;     // Lower bound of 70% volume area
    private final long valueAreaVolume;    // Volume within value area
    private final double vpin;             // VPIN metric (0.0 to 1.0)
    private final double vpinBucketSize;   // Adaptive bucket size used
    private final int vpinBucketCount;     // Number of buckets accumulated

    private VolumeProfile(Builder builder) {
        this.poc = builder.poc;
        this.valueAreaHigh = builder.valueAreaHigh;
        this.valueAreaLow = builder.valueAreaLow;
        this.valueAreaVolume = builder.valueAreaVolume;
        this.vpin = builder.vpin;
        this.vpinBucketSize = builder.vpinBucketSize;
        this.vpinBucketCount = builder.vpinBucketCount;

        // Validate
        if (valueAreaHigh != null && valueAreaLow != null && valueAreaHigh < valueAreaLow) {
            throw new IllegalArgumentException("Value area high < low");
        }
        if (vpin < 0 || vpin > 1) {
            throw new IllegalArgumentException("VPIN must be between 0 and 1, got: " + vpin);
        }
    }

    // Getters
    public Double getPoc() { return poc; }
    public Double getValueAreaHigh() { return valueAreaHigh; }
    public Double getValueAreaLow() { return valueAreaLow; }
    public long getValueAreaVolume() { return valueAreaVolume; }
    public double getVpin() { return vpin; }
    public double getVpinBucketSize() { return vpinBucketSize; }
    public int getVpinBucketCount() { return vpinBucketCount; }

    /**
     * Check if POC is available.
     */
    public boolean hasPOC() {
        return poc != null;
    }

    /**
     * Check if value area is defined.
     */
    public boolean hasValueArea() {
        return valueAreaHigh != null && valueAreaLow != null;
    }

    /**
     * Get value area range.
     */
    public double getValueAreaRange() {
        return hasValueArea() ? valueAreaHigh - valueAreaLow : 0.0;
    }

    /**
     * Check if VPIN indicates high toxic flow.
     * VPIN > 0.5 suggests informed trading activity.
     */
    public boolean hasHighToxicFlow() {
        return vpin > 0.5;
    }

    /**
     * Check if VPIN bucket count is sufficient for reliable reading.
     */
    public boolean hasReliableVPIN() {
        return vpinBucketCount >= 20;  // Need at least 20 buckets
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VolumeProfile that = (VolumeProfile) o;
        return Double.compare(that.vpin, vpin) == 0 &&
               Objects.equals(poc, that.poc) &&
               Objects.equals(valueAreaHigh, that.valueAreaHigh) &&
               Objects.equals(valueAreaLow, that.valueAreaLow);
    }

    @Override
    public int hashCode() {
        return Objects.hash(poc, valueAreaHigh, valueAreaLow, vpin);
    }

    @Override
    public String toString() {
        return String.format("VolumeProfile{POC=%.2f VA=[%.2f-%.2f] VPIN=%.3f}",
            poc, valueAreaLow, valueAreaHigh, vpin);
    }

    public static class Builder {
        private Double poc;
        private Double valueAreaHigh;
        private Double valueAreaLow;
        private long valueAreaVolume;
        private double vpin;
        private double vpinBucketSize;
        private int vpinBucketCount;

        public Builder poc(Double poc) {
            this.poc = poc;
            return this;
        }

        public Builder valueAreaHigh(Double valueAreaHigh) {
            this.valueAreaHigh = valueAreaHigh;
            return this;
        }

        public Builder valueAreaLow(Double valueAreaLow) {
            this.valueAreaLow = valueAreaLow;
            return this;
        }

        public Builder valueAreaVolume(long valueAreaVolume) {
            this.valueAreaVolume = valueAreaVolume;
            return this;
        }

        public Builder vpin(double vpin) {
            this.vpin = vpin;
            return this;
        }

        public Builder vpinBucketSize(double vpinBucketSize) {
            this.vpinBucketSize = vpinBucketSize;
            return this;
        }

        public Builder vpinBucketCount(int vpinBucketCount) {
            this.vpinBucketCount = vpinBucketCount;
            return this;
        }

        public VolumeProfile build() {
            return new VolumeProfile(this);
        }
    }

    public static VolumeProfile empty() {
        return new Builder().build();
    }
}
