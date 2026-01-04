package com.kotsin.consumer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * IPU Configuration - All tunable parameters for Institutional Participation & Urgency
 * 
 * Properties can be overridden via application.properties:
 * ipu:
 *   volume-sma-period: 20
 *   momentum-lookback: 3
 *   ...
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ipu")
public class IPUConfig {

    // ========== Volume Parameters ==========
    
    /**
     * Period for volume SMA calculation
     */
    private int volumeSmaPeriod = 20;
    
    /**
     * Scale factor for exponential volume scoring
     */
    private double volumeScaleFactor = 3.0;

    // ========== Price Efficiency Parameters ==========
    
    /**
     * Maximum wick penalty (0.3 = 30% max reduction)
     */
    private double efficiencyWickPenaltyMax = 0.30;
    
    /**
     * Epsilon to avoid division by zero
     */
    private double epsilon = 1e-10;

    // ========== Order Flow Parameters ==========
    
    /**
     * Scale factor for OFI normalization
     */
    private double ofiScaleFactor = 2.0;
    
    /**
     * Threshold for significant depth imbalance
     */
    private double depthImbalanceThreshold = 0.5;
    
    /**
     * Bonus multiplier when all flow signals agree
     */
    private double flowAgreementBonus = 1.3;

    // ========== Kyle's Lambda Parameters ==========
    
    /**
     * Maximum boost from Kyle's Lambda
     */
    private double lambdaBoostMax = 0.3;
    
    /**
     * Lambda scaling factor
     */
    private double lambdaScale = 0.5;

    // ========== Momentum Parameters ==========

    /**
     * Bars for momentum slope calculation
     */
    private int momentumLookback = 3;

    /**
     * Threshold for normalizing acceleration
     */
    private double accelerationThreshold = 0.25;

    /**
     * Maximum slope magnitude (clamp)
     */
    private double slopeClamp = 1.0;

    /**
     * Momentum decline threshold for exhaustion
     */
    private double exhaustionDeclineThreshold = 0.8;

    /**
     * Volume decline threshold for exhaustion
     */
    private double exhaustionVolumeDecline = 0.7;

    /**
     * Wick ratio threshold for exhaustion
     */
    private double exhaustionWickThreshold = 0.4;

    // ========== Momentum State Classification Thresholds ==========
    // These determine when momentum is classified as ACCELERATING, TRENDING, etc.
    // Previously hardcoded magic numbers - now configurable

    /**
     * Minimum acceleration to classify as ACCELERATING/DECELERATING
     * (previously hardcoded as 0.1)
     */
    private double momentumStateAccelThreshold = 0.1;

    /**
     * Minimum slope magnitude to consider momentum "strong" for ACCELERATING/DECELERATING
     * (previously hardcoded as 0.4)
     */
    private double momentumStateSlopeStrong = 0.4;

    /**
     * Minimum slope magnitude for TRENDING state
     * (previously hardcoded as 0.5)
     */
    private double momentumStateTrendingThreshold = 0.5;

    /**
     * Minimum slope magnitude for DRIFTING state (between FLAT and TRENDING)
     * (previously hardcoded as 0.25)
     */
    private double momentumStateDriftingThreshold = 0.25;

    // ========== Urgency Parameters ==========
    
    /**
     * Tick density scale factor
     */
    private double tickDensityScale = 2.0;
    
    /**
     * Momentum ATR scale for urgency
     */
    private double momentumAtrScale = 1.5;
    
    /**
     * Volume acceleration threshold
     */
    private double volAccelerationThreshold = 0.5;
    
    /**
     * Boost for accelerating + high flow agreement
     */
    private double acceleratingUrgencyBoost = 1.15;
    
    /**
     * Penalty for decelerating momentum
     */
    private double deceleratingUrgencyPenalty = 0.90;

    // ========== X-Factor Thresholds ==========
    
    private double xfactorVolThreshold = 0.65;
    private double xfactorEfficiencyThreshold = 0.35;
    private double xfactorFlowThreshold = 0.6;
    private double xfactorDirectionThreshold = 0.75;
    private double xfactorMomentumThreshold = 0.5;
    private double xfactorOiBoost = 1.2;
    private double xfactorOiThreshold = 0.5;

    // ========== Final Score Modifiers ==========
    
    private double momentumModifierStrength = 0.15;
    private double urgencyModifierStrength = 0.15;
    private double xfactorModifierStrength = 0.25;
    private double exhaustionPenaltyStrength = 0.20;

    // ========== Filters ==========
    
    /**
     * Minimum volume for valid signal
     */
    private long minVolumeThreshold = 1000;
    
    /**
     * Minimum depth for valid signal
     */
    private double minDepthThreshold = 500;
    
    /**
     * Opening range filter duration (minutes)
     */
    private int openingRangeMinutes = 30;
    
    /**
     * News spike volume threshold
     */
    private double newsSpikeVolThreshold = 8.0;
    
    /**
     * Gap threshold in ATR multiples
     */
    private double gapThresholdAtr = 1.0;

    // ========== Multi-Timeframe Weights ==========
    
    private double mtfWeight5m = 0.40;
    private double mtfWeight15m = 0.35;
    private double mtfWeight30m = 0.25;

    // ========== Direction Voting Weights ==========
    
    private double priceDirectionWeight = 1.0;
    private double flowDirectionWeight = 1.0;
    private double ofiDirectionWeight = 0.8;
    private double depthDirectionWeight = 0.7;
    private double momentumDirectionWeight = 1.2;

    // ========== Convenience Methods ==========

    /**
     * Get total direction vote weight
     */
    public double getMaxDirectionVotes() {
        return priceDirectionWeight + flowDirectionWeight + ofiDirectionWeight 
             + depthDirectionWeight + momentumDirectionWeight;
    }

    /**
     * Get weight for timeframe
     */
    public double getWeightForTimeframe(String timeframe) {
        switch (timeframe) {
            case "5m": return mtfWeight5m;
            case "15m": return mtfWeight15m;
            case "30m": return mtfWeight30m;
            default: return 0.0;
        }
    }
}
