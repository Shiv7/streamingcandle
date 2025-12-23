package com.kotsin.consumer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * VCP Configuration - All tunable parameters for Volume Cluster Pivot calculation
 * 
 * Properties can be overridden via application.yml:
 * vcp:
 *   price-bin-size: 0.0005
 *   lookback-5m: 48
 *   ...
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "vcp")
public class VCPConfig {

    // ========== Volume Profile Parameters ==========
    
    /**
     * Price bin size as a fraction (0.0005 = 0.05%)
     * Used for volume profile aggregation
     */
    private double priceBinSize = 0.0005;

    /**
     * Lookback candles for 5m timeframe (48 = 4 hours)
     */
    private int lookback5m = 48;

    /**
     * Lookback candles for 15m timeframe (32 = 8 hours)
     */
    private int lookback15m = 32;

    /**
     * Lookback candles for 30m timeframe (24 = 12 hours)
     */
    private int lookback30m = 24;

    // ========== Cluster Detection Parameters ==========

    /**
     * Minimum normalized cluster strength to qualify as a cluster
     */
    private double minClusterStrength = 0.02;

    /**
     * Maximum number of clusters to return per timeframe
     */
    private int maxClusters = 5;

    /**
     * Standard deviations above mean for peak detection
     * Lower = more clusters, higher = fewer but stronger
     */
    private double peakThresholdSigma = 0.5;

    /**
     * Minimum volume percentile to qualify as a cluster
     * Clusters must be in top N% of volume levels
     */
    private double minVolumePercentile = 70.0;

    // ========== Order Book Validation Parameters ==========

    /**
     * Radius around cluster price to check order book depth (as fraction)
     * 0.005 = 0.5% on each side
     */
    private double obValidationRadius = 0.005;

    /**
     * Order book validation threshold for "strong" validation
     * If current depth / average depth > this, cluster is strongly validated
     */
    private double obValidationStrong = 1.5;

    /**
     * Order book validation threshold for "weak" validation
     * If current depth / average depth < this, cluster may be stale
     */
    private double obValidationWeak = 0.5;

    // ========== OI Adjustment Parameters ==========

    /**
     * Maximum OI adjustment factor (cap)
     * OI can strengthen cluster by at most 50% or weaken by at most 50%
     */
    private double oiAdjustmentCap = 0.5;

    /**
     * Lookback candles for OI change calculation
     */
    private int oiLookback = 20;

    // ========== Proximity Calculation Parameters ==========

    /**
     * Exponential decay constant for proximity calculation
     * Smaller = slower decay, larger = faster decay
     */
    private double proximityDecayConstant = 0.005;

    /**
     * Multiplier for spread factor in proximity calculation
     * Higher = wider spreads make clusters feel further away
     */
    private double spreadFactorMultiplier = 10.0;

    /**
     * Maximum distance (as fraction) to consider a cluster relevant
     * Clusters further than this get proximity = 0
     */
    private double maxRelevantDistance = 0.02;  // 2%

    // ========== Kyle's Lambda / Penetration Parameters ==========

    /**
     * Fraction of cluster volume assumed needed to break through
     * Used for penetration difficulty calculation
     */
    private double breakoutVolumeFraction = 0.3;

    /**
     * ATR multiplier for high difficulty threshold
     * If penetration impact > ATR * this, cluster is hard to break
     */
    private double highDifficultyAtrMultiple = 1.0;

    // ========== Multi-Timeframe Fusion Weights ==========

    /**
     * Weight for 5-minute VCP in combined score
     */
    private double weight5m = 0.50;

    /**
     * Weight for 15-minute VCP in combined score
     */
    private double weight15m = 0.30;

    /**
     * Weight for 30-minute VCP in combined score
     */
    private double weight30m = 0.20;

    // ========== Scoring Parameters ==========

    /**
     * OFI bias modifier strength
     * How much directional conviction affects cluster score
     */
    private double ofiModifierStrength = 0.2;

    /**
     * Epsilon for bias calculation to avoid division by zero
     */
    private double biasEpsilon = 0.001;

    // ========== ATR Parameters ==========

    /**
     * ATR period for volatility calculation
     */
    private int atrPeriod = 14;

    /**
     * Default ATR as fraction of price if not enough data
     */
    private double defaultAtrFraction = 0.01;  // 1%

    // ========== Convenience Methods ==========

    /**
     * Get lookback for a specific timeframe
     */
    public int getLookbackForTimeframe(String timeframe) {
        switch (timeframe) {
            case "5m":
                return lookback5m;
            case "15m":
                return lookback15m;
            case "30m":
                return lookback30m;
            default:
                return lookback5m;
        }
    }

    /**
     * Get weight for a specific timeframe
     */
    public double getWeightForTimeframe(String timeframe) {
        switch (timeframe) {
            case "5m":
                return weight5m;
            case "15m":
                return weight15m;
            case "30m":
                return weight30m;
            default:
                return 0.0;
        }
    }

    /**
     * Validate configuration values
     */
    public void validate() {
        if (priceBinSize <= 0 || priceBinSize > 0.1) {
            throw new IllegalArgumentException("priceBinSize must be between 0 and 0.1");
        }
        if (peakThresholdSigma < 0 || peakThresholdSigma > 3) {
            throw new IllegalArgumentException("peakThresholdSigma must be between 0 and 3");
        }
        double totalWeight = weight5m + weight15m + weight30m;
        if (Math.abs(totalWeight - 1.0) > 0.001) {
            throw new IllegalArgumentException("Timeframe weights must sum to 1.0, got " + totalWeight);
        }
    }
}
