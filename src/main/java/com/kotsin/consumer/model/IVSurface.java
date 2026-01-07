package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * IVSurface - Implied Volatility Surface Analytics for options.
 *
 * Provides comprehensive volatility analysis:
 * - Smile curve (IV by strike)
 * - Skew metrics (put vs call IV)
 * - Term structure (IV by expiry)
 * - IV dynamics (rank, velocity, crush risk)
 *
 * CRITICAL FOR OPTIONS STRATEGY SELECTION:
 * - High IV rank → Sell premium strategies
 * - Low IV rank → Buy premium strategies
 * - Steep skew → Put spreads may be expensive
 * - IV crush risk → Avoid long premium before events
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class IVSurface {

    // ========================================================================
    // SMILE CURVE - IV by strike
    // ========================================================================

    /**
     * Call IV at each strike price
     * Map: strike → implied volatility (as decimal, e.g., 0.25 for 25%)
     */
    private Map<Double, Double> callIVByStrike;

    /**
     * Put IV at each strike price
     * Map: strike → implied volatility
     */
    private Map<Double, Double> putIVByStrike;

    /**
     * At-The-Money implied volatility
     * Average of ATM call and put IV
     */
    private double atmIV;

    /**
     * Smile slope = rate of IV change per strike
     * Positive = IV increases away from ATM
     */
    private double smileSlope;

    /**
     * Smile curvature (kurtosis)
     * Higher = more pronounced smile
     */
    private double smileCurvature;

    /**
     * Smile shape classification
     */
    private SmileShape smileShape;

    public enum SmileShape {
        NORMAL,         // Typical smile with higher OTM IV
        STEEP_PUT,      // Put skew - higher IV for OTM puts (bearish expectation)
        STEEP_CALL,     // Call skew - higher IV for OTM calls (rare, bullish expectation)
        FLAT,           // Uniform IV across strikes (unusual)
        SMIRK           // Asymmetric smile (common in equity options)
    }

    // ========================================================================
    // SKEW METRICS - Put vs Call IV
    // ========================================================================

    /**
     * 25-Delta Skew = IV(25Δ put) - IV(25Δ call)
     * Positive = puts more expensive (bearish hedging demand)
     * Range: typically -5% to +10%
     */
    private double skew25Delta;

    /**
     * 10-Delta Skew = IV(10Δ put) - IV(10Δ call)
     * Measures far OTM skew
     */
    private double skew10Delta;

    /**
     * Risk Reversal = 25Δ call IV - 25Δ put IV
     * Negative = put premium (bearish bias)
     * Positive = call premium (bullish bias)
     */
    private double riskReversal;

    /**
     * Butterfly Spread = 0.5 × (25Δ call IV + 25Δ put IV) - ATM IV
     * Measures smile convexity/curvature
     */
    private double butterflySpread;

    /**
     * ATM Call IV
     */
    private Double atmCallIV;

    /**
     * ATM Put IV
     */
    private Double atmPutIV;

    // ========================================================================
    // TERM STRUCTURE - IV by expiry
    // ========================================================================

    /**
     * ATM IV at each expiry date
     * Map: expiry string → ATM IV for that expiry
     */
    private Map<String, Double> ivByExpiry;

    /**
     * Term slope = IV difference between near and far expiries
     * Positive = contango (far > near)
     * Negative = backwardation (near > far)
     */
    private double termSlope;

    /**
     * Term structure classification
     */
    private TermStructure termStructure;

    public enum TermStructure {
        CONTANGO,       // Far IV > Near IV (normal market)
        BACKWARDATION,  // Near IV > Far IV (event/stress)
        FLAT            // Uniform IV across expiries
    }

    /**
     * Near-term ATM IV (nearest expiry)
     */
    private Double nearTermIV;

    /**
     * Far-term ATM IV (farthest expiry)
     */
    private Double farTermIV;

    // ========================================================================
    // IV DYNAMICS - Rank, Velocity, Crush Risk
    // ========================================================================

    /**
     * IV Rank = current IV percentile over last 252 days
     * Range: 0-100
     * > 80 = High IV (sell premium)
     * < 20 = Low IV (buy premium)
     */
    private double ivRank;

    /**
     * IV Percentile = % of days IV was lower than current
     * Similar to IV Rank but calculated differently
     */
    private Double ivPercentile;

    /**
     * IV change over last 1 minute (%)
     */
    private double ivChange1m;

    /**
     * IV change over last 5 minutes (%)
     */
    private double ivChange5m;

    /**
     * IV velocity = rate of IV change (dIV/dt)
     * Positive = IV rising, Negative = IV falling
     */
    private double ivVelocity;

    /**
     * IV crush risk flag
     * True if: high IV + near expiry + event approaching
     */
    private boolean ivCrushRisk;

    /**
     * Historical IV for comparison
     */
    private Double historicalIV;

    /**
     * IV premium = Current IV - Historical IV
     * Positive = IV is elevated
     */
    private Double ivPremium;

    // ========================================================================
    // ACTIONABLE SIGNALS
    // ========================================================================

    /**
     * IV-based trading signal
     */
    private IVSignal ivSignal;

    /**
     * Signal strength (0-1)
     */
    private double ivSignalStrength;

    public enum IVSignal {
        BUY_VOL,        // Low IV rank, buy premium strategies
        SELL_VOL,       // High IV rank, sell premium strategies
        NEUTRAL,        // No clear signal
        CRUSH_WARNING   // IV crush risk, avoid long premium
    }

    // ========================================================================
    // CONVENIENCE METHODS
    // ========================================================================

    /**
     * Check if IV is elevated (> 70th percentile)
     */
    public boolean isIVElevated() {
        return ivRank > 70;
    }

    /**
     * Check if IV is depressed (< 30th percentile)
     */
    public boolean isIVDepressed() {
        return ivRank < 30;
    }

    /**
     * Check if put skew is extreme (puts significantly more expensive)
     */
    public boolean hasExtremePutSkew() {
        return skew25Delta > 5.0; // 5% skew is extreme
    }

    /**
     * Check if term structure is inverted (backwardation)
     */
    public boolean isTermStructureInverted() {
        return termStructure == TermStructure.BACKWARDATION;
    }

    /**
     * Get recommended strategy based on IV environment
     */
    public String getRecommendedStrategy() {
        if (ivSignal == IVSignal.SELL_VOL) {
            return hasExtremePutSkew() ? "SELL_PUT_SPREAD" : "SELL_STRADDLE";
        } else if (ivSignal == IVSignal.BUY_VOL) {
            return "BUY_STRADDLE";
        } else if (ivSignal == IVSignal.CRUSH_WARNING) {
            return "AVOID_LONG_PREMIUM";
        } else {
            return "NEUTRAL";
        }
    }

    /**
     * Check if volatility surface data is available
     */
    public boolean hasData() {
        return atmIV > 0 || (callIVByStrike != null && !callIVByStrike.isEmpty());
    }

    /**
     * Factory method for empty surface
     */
    public static IVSurface empty() {
        return IVSurface.builder()
            .atmIV(0)
            .ivRank(50) // Neutral rank
            .smileShape(SmileShape.FLAT)
            .termStructure(TermStructure.FLAT)
            .ivSignal(IVSignal.NEUTRAL)
            .ivSignalStrength(0)
            .build();
    }
}
