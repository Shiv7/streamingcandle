package com.kotsin.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * GreeksPortfolio - Family-level aggregated Greeks for risk management.
 *
 * Aggregates delta, gamma, vega, theta across all options in a family
 * to provide institutional-grade risk metrics:
 * - Total directional exposure (delta)
 * - Convexity risk (gamma) and gamma squeeze detection
 * - Volatility exposure (vega) by expiry
 * - Time decay profile (theta)
 *
 * CRITICAL FOR QUANT DESKS:
 * - Proper position sizing based on Greeks
 * - Gamma squeeze detection
 * - Vega bucketing for volatility regime changes
 * - Daily theta decay P&L estimation
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class GreeksPortfolio {

    // ========================================================================
    // AGGREGATED GREEKS (weighted by Open Interest)
    // ========================================================================

    /**
     * Total delta exposure = sum of (option_delta × OI) for all options
     * Positive = net long exposure, Negative = net short exposure
     * Units: Equivalent underlying shares
     */
    private double totalDelta;

    /**
     * Total gamma exposure = sum of (option_gamma × OI)
     * Higher = more convexity, larger delta changes with price moves
     * Units: Delta change per point move
     */
    private double totalGamma;

    /**
     * Total vega exposure = sum of (option_vega × OI)
     * P&L change per 1% IV move
     * Units: Currency (₹) per 1% IV change
     */
    private double totalVega;

    /**
     * Total theta exposure = sum of (option_theta × OI)
     * Daily time decay (typically negative)
     * Units: Currency (₹) per day
     */
    private double totalTheta;

    // ========================================================================
    // DELTA LADDER - Directional exposure by strike
    // ========================================================================

    /**
     * Delta exposure at each strike price
     * Map: strike → net delta at that strike
     * Positive = call-heavy, Negative = put-heavy
     */
    private Map<Double, Double> deltaByStrike;

    /**
     * Delta-weighted average strike price
     * Shows the "center of gravity" of directional exposure
     */
    private double deltaWeightedAvgStrike;

    /**
     * Net directional bias based on delta
     */
    private DeltaBias deltaBias;

    /**
     * Call delta exposure (sum of call deltas × OI)
     */
    private double callDeltaExposure;

    /**
     * Put delta exposure (sum of put deltas × OI, typically negative)
     */
    private double putDeltaExposure;

    public enum DeltaBias {
        LONG,       // Net positive delta (bullish exposure)
        SHORT,      // Net negative delta (bearish exposure)
        NEUTRAL     // Near-zero delta (hedged)
    }

    // ========================================================================
    // GAMMA LADDER - Convexity by strike (Gamma Squeeze Detection)
    // ========================================================================

    /**
     * Gamma exposure at each strike price
     * Map: strike → gamma exposure at that strike
     */
    private Map<Double, Double> gammaByStrike;

    /**
     * Strike with maximum gamma exposure
     * Price approaching this strike = gamma squeeze risk
     */
    private double maxGammaStrike;

    /**
     * Gamma concentration = % of total gamma in top 3 strikes
     * High concentration = more squeeze risk
     */
    private double gammaConcentration;

    /**
     * Gamma squeeze risk flag
     * True if: high gamma concentration AND price near max gamma strike
     */
    private boolean gammaSqueezeRisk;

    /**
     * Distance from current price to max gamma strike (%)
     * Smaller = higher squeeze risk
     */
    private Double gammaSqueezeDistance;

    // ========================================================================
    // VEGA BUCKETING - Volatility exposure by expiry
    // ========================================================================

    /**
     * Vega exposure by expiry date
     * Map: expiry string → vega exposure for that expiry
     */
    private Map<String, Double> vegaByExpiry;

    /**
     * Near-term vega (options expiring within 7 days)
     * More sensitive to short-term volatility
     */
    private double nearTermVega;

    /**
     * Far-term vega (options expiring beyond 30 days)
     * More sensitive to term structure changes
     */
    private double farTermVega;

    /**
     * Vega structure classification
     */
    private VegaStructure vegaStructure;

    public enum VegaStructure {
        FRONT_HEAVY,    // More vega in near-term (sensitive to spot vol)
        BACK_HEAVY,     // More vega in far-term (sensitive to term structure)
        BALANCED        // Even distribution
    }

    // ========================================================================
    // THETA PROFILE - Time decay analysis
    // ========================================================================

    /**
     * Expected daily P&L from theta decay
     * Typically negative for long options
     */
    private double dailyThetaDecay;

    /**
     * Expected weekend theta decay (Friday to Monday)
     * Usually 3x daily theta
     */
    private double weekendThetaDecay;

    /**
     * Theta by expiry bucket
     * Map: expiry string → theta for that expiry
     */
    private Map<String, Double> thetaByExpiry;

    // ========================================================================
    // RISK METRICS
    // ========================================================================

    /**
     * Spot price move needed to change delta by 1%
     * Higher = more stable position
     */
    private Double spotMoveFor1PctDelta;

    /**
     * Portfolio gamma in currency terms
     * P&L change from 1 point spot move squared
     */
    private double gammaRisk;

    /**
     * Vega risk = P&L per 1% IV change
     * Same as totalVega but explicitly named
     */
    private double vegaRisk;

    /**
     * Overall portfolio risk score (0-100)
     * Higher = more risky position
     */
    private double riskScore;

    // ========================================================================
    // CONVENIENCE METHODS
    // ========================================================================

    /**
     * Check if portfolio is delta neutral (within threshold)
     */
    public boolean isDeltaNeutral() {
        return Math.abs(totalDelta) < 100; // Configurable threshold
    }

    /**
     * Check if gamma squeeze is imminent
     */
    public boolean isGammaSqueezeImminent() {
        return gammaSqueezeRisk && gammaSqueezeDistance != null && gammaSqueezeDistance < 1.0;
    }

    /**
     * Get estimated P&L from 1 point spot move
     * Accounts for both delta and gamma
     */
    public double estimatePnL(double spotMove) {
        // P&L ≈ delta × move + 0.5 × gamma × move²
        return totalDelta * spotMove + 0.5 * totalGamma * spotMove * spotMove;
    }

    /**
     * Get estimated P&L from 1% IV change
     */
    public double estimateVegaPnL(double ivChange) {
        return totalVega * ivChange;
    }

    /**
     * Check if position is net long volatility
     */
    public boolean isLongVolatility() {
        return totalVega > 0;
    }

    /**
     * Check if position is net short volatility
     */
    public boolean isShortVolatility() {
        return totalVega < 0;
    }

    /**
     * Get maximum risk strike (where gamma is highest)
     */
    public double getMaxRiskStrike() {
        return maxGammaStrike;
    }

    /**
     * Factory method for empty portfolio
     */
    public static GreeksPortfolio empty() {
        return GreeksPortfolio.builder()
            .totalDelta(0)
            .totalGamma(0)
            .totalVega(0)
            .totalTheta(0)
            .deltaBias(DeltaBias.NEUTRAL)
            .gammaSqueezeRisk(false)
            .vegaStructure(VegaStructure.BALANCED)
            .riskScore(0)
            .build();
    }

    /**
     * Check if portfolio has meaningful exposure
     */
    public boolean hasExposure() {
        return Math.abs(totalDelta) > 0 ||
               Math.abs(totalGamma) > 0 ||
               Math.abs(totalVega) > 0;
    }
}
