package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * VCPCluster - Individual Volume Cluster with Enrichments
 * 
 * Represents a price level where significant volume has accumulated,
 * enriched with:
 * - OFI bias (directional context)
 * - Order book validation (current depth confirmation)
 * - OI adjustment (position building/unwinding)
 * - Kyle's Lambda penetration difficulty
 * - Liquidity-adjusted proximity to current price
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class VCPCluster {

    /**
     * Price level of the cluster center
     */
    private double price;

    /**
     * Normalized volume strength (0 to 1)
     * Higher = more volume accumulated at this level
     */
    private double strength;

    /**
     * Total volume at this cluster
     */
    private long totalVolume;

    /**
     * OFI Bias: -1 (pure selling) to +1 (pure buying)
     * Indicates whether cluster formed from buying or selling pressure.
     * 
     * Positive bias below price = demand zone (support)
     * Negative bias above price = supply zone (resistance)
     */
    private double ofiBias;

    /**
     * Order book validation score
     * Ratio of current depth at cluster vs average depth.
     * 
     * > 1.5 = strong validation (order book stacked at this level)
     * < 0.5 = weak validation (historical cluster, thin current book)
     */
    private double obValidation;

    /**
     * OI-based strength adjustment multiplier
     * 
     * > 1.0 = positions being built, cluster strengthening
     * < 1.0 = positions being unwound, cluster weakening
     */
    private double oiAdjustment;

    /**
     * Breakout difficulty based on Kyle's Lambda
     * Estimated relative difficulty to push through this cluster.
     * 
     * > 1.0 = requires more than 1 ATR worth of impact to break
     * < 0.3 = relatively easy to penetrate
     */
    private double breakoutDifficulty;

    /**
     * Liquidity-adjusted proximity to current price (0 to 1)
     * Accounts for spread and volatility.
     * 
     * Higher proximity = closer and more relevant
     * 1.0 = at cluster level
     * 0.0 = far away (> 0.7% or decayed)
     */
    private double proximity;

    /**
     * Cluster type: SUPPORT or RESISTANCE
     * Based on position relative to current price
     */
    private ClusterType type;

    /**
     * Raw distance from current price (percentage)
     */
    private double distancePercent;

    /**
     * Number of candles that contributed to this cluster
     */
    private int contributingCandles;

    /**
     * Composite score combining all factors
     * Used for final ranking and downstream signals
     */
    private double compositeScore;

    public enum ClusterType {
        SUPPORT,
        RESISTANCE
    }

    /**
     * Calculate composite score from all enrichments
     */
    public void calculateCompositeScore() {
        // Base strength
        double score = strength;

        // Apply OFI modifier (directional conviction increases score)
        double ofiModifier = 1.0 + 0.2 * Math.abs(ofiBias);
        score *= ofiModifier;

        // Apply order book validation (capped at 2x)
        double obModifier = Math.min(obValidation, 2.0);
        score *= obModifier;

        // Apply OI adjustment
        score *= oiAdjustment;

        // Weight by proximity
        score *= proximity;

        this.compositeScore = Math.min(score, 1.0);  // Cap at 1.0
    }

    /**
     * Determine if this cluster aligns with its type
     * (buying cluster for support, selling cluster for resistance)
     */
    public boolean isAligned() {
        if (type == ClusterType.SUPPORT) {
            return ofiBias > 0;  // Buying cluster = good support
        } else {
            return ofiBias < 0;  // Selling cluster = good resistance
        }
    }

    /**
     * Get alignment multiplier for directional scoring
     */
    public double getAlignmentMultiplier() {
        if (isAligned()) {
            return 1.0 + Math.abs(ofiBias);  // Boost aligned clusters
        } else {
            return 0.5;  // Penalize misaligned clusters
        }
    }

    /**
     * Check if cluster is strongly validated
     */
    public boolean isStronglyValidated() {
        return obValidation >= 1.5 && oiAdjustment >= 1.0;
    }

    /**
     * Check if cluster is weakly validated (potentially stale)
     */
    public boolean isWeaklyValidated() {
        return obValidation < 0.5 || oiAdjustment < 0.8;
    }

    /**
     * Builder helper for creating support cluster
     */
    public static VCPCluster supportCluster(double price, double strength, long volume) {
        return VCPCluster.builder()
                .price(price)
                .strength(strength)
                .totalVolume(volume)
                .type(ClusterType.SUPPORT)
                .obValidation(1.0)
                .oiAdjustment(1.0)
                .build();
    }

    /**
     * Builder helper for creating resistance cluster
     */
    public static VCPCluster resistanceCluster(double price, double strength, long volume) {
        return VCPCluster.builder()
                .price(price)
                .strength(strength)
                .totalVolume(volume)
                .type(ClusterType.RESISTANCE)
                .obValidation(1.0)
                .oiAdjustment(1.0)
                .build();
    }
}
