package com.kotsin.consumer.service;

import com.kotsin.consumer.domain.model.OptionCandle;
import com.kotsin.consumer.model.GreeksPortfolio;
import com.kotsin.consumer.util.BlackScholesGreeks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GreeksAggregator - Aggregates option Greeks across a family for risk management.
 *
 * Calculates:
 * - Total delta/gamma/vega/theta exposure (OI-weighted)
 * - Delta ladder (directional exposure by strike)
 * - Gamma ladder with squeeze detection
 * - Vega bucketing by expiry
 * - Risk metrics
 *
 * INSTITUTIONAL GRADE: Proper position sizing and risk management requires
 * understanding aggregated Greeks exposure, not just individual option Greeks.
 */
@Service
@Slf4j
public class GreeksAggregator {

    // Configurable thresholds
    private static final double DELTA_NEUTRAL_THRESHOLD = 100.0;      // Contracts
    private static final double GAMMA_SQUEEZE_DISTANCE_PCT = 2.0;     // % from max gamma strike
    private static final double GAMMA_CONCENTRATION_THRESHOLD = 0.6;  // 60% in top 3 strikes
    private static final int NEAR_TERM_DTE = 7;                       // Days to expiry
    private static final int FAR_TERM_DTE = 30;

    /**
     * Aggregate Greeks from list of option candles
     *
     * @param options List of OptionCandle in the family
     * @param spotPrice Current spot price for gamma squeeze detection
     * @return GreeksPortfolio with aggregated metrics
     */
    public GreeksPortfolio aggregate(List<OptionCandle> options, double spotPrice) {
        if (options == null || options.isEmpty()) {
            return GreeksPortfolio.empty();
        }

        // Initialize aggregation containers
        double totalDelta = 0.0;
        double totalGamma = 0.0;
        double totalVega = 0.0;
        double totalTheta = 0.0;

        double callDeltaExposure = 0.0;
        double putDeltaExposure = 0.0;

        Map<Double, Double> deltaByStrike = new HashMap<>();
        Map<Double, Double> gammaByStrike = new HashMap<>();
        Map<String, Double> vegaByExpiry = new HashMap<>();
        Map<String, Double> thetaByExpiry = new HashMap<>();

        double nearTermVega = 0.0;
        double farTermVega = 0.0;

        double deltaWeightedStrikeSum = 0.0;
        double totalAbsDelta = 0.0;

        // Aggregate across all options
        for (OptionCandle opt : options) {
            if (opt == null) continue;

            long oi = opt.getOpenInterest();
            if (oi <= 0) continue;

            Double delta = opt.getDelta();
            Double gamma = opt.getGamma();
            Double vega = opt.getVega();
            Double theta = opt.getTheta();
            double strike = opt.getStrikePrice();
            String expiry = opt.getExpiry();
            boolean isCall = opt.isCall();

            // FIX: Calculate Greeks if not present using Black-Scholes
            // This fixes the bug where underlying price was incorrectly set to option premium
            if (delta == null || gamma == null || vega == null || theta == null) {
                if (strike > 0 && spotPrice > 0 && expiry != null) {
                    try {
                        int dte = estimateDTE(expiry);
                        if (dte > 0) {
                            double timeToExpiryYears = dte / 365.0;
                            double volatility = opt.getImpliedVolatility() != null ? opt.getImpliedVolatility() : 0.30;
                            BlackScholesGreeks.GreeksResult greeks = BlackScholesGreeks.calculateGreeks(
                                spotPrice, strike, timeToExpiryYears, volatility, isCall
                            );
                            if (delta == null) delta = greeks.delta;
                            if (gamma == null) gamma = greeks.gamma;
                            if (vega == null) vega = greeks.vega;
                            if (theta == null) theta = greeks.theta;
                        }
                    } catch (Exception e) {
                        log.debug("Failed to calculate Greeks for option strike {}: {}", strike, e.getMessage());
                    }
                }
            }

            // Aggregate total Greeks (weighted by OI)
            if (delta != null) {
                double deltaExposure = delta * oi;
                totalDelta += deltaExposure;

                if (isCall) {
                    callDeltaExposure += deltaExposure;
                } else {
                    putDeltaExposure += deltaExposure;
                }

                // Delta by strike
                deltaByStrike.merge(strike, deltaExposure, Double::sum);

                // For weighted average strike
                deltaWeightedStrikeSum += strike * Math.abs(deltaExposure);
                totalAbsDelta += Math.abs(deltaExposure);
            }

            if (gamma != null) {
                double gammaExposure = gamma * oi;
                totalGamma += gammaExposure;
                gammaByStrike.merge(strike, gammaExposure, Double::sum);
            }

            if (vega != null) {
                double vegaExposure = vega * oi;
                totalVega += vegaExposure;

                // Vega by expiry
                if (expiry != null) {
                    vegaByExpiry.merge(expiry, vegaExposure, Double::sum);

                    // Near/far term classification
                    int dte = estimateDTE(expiry);
                    if (dte <= NEAR_TERM_DTE) {
                        nearTermVega += vegaExposure;
                    } else if (dte >= FAR_TERM_DTE) {
                        farTermVega += vegaExposure;
                    }
                }
            }

            if (theta != null) {
                double thetaExposure = theta * oi;
                totalTheta += thetaExposure;

                if (expiry != null) {
                    thetaByExpiry.merge(expiry, thetaExposure, Double::sum);
                }
            }
        }

        // Calculate delta-weighted average strike
        double deltaWeightedAvgStrike = totalAbsDelta > 0
            ? deltaWeightedStrikeSum / totalAbsDelta
            : spotPrice;

        // Determine delta bias
        GreeksPortfolio.DeltaBias deltaBias = determineDeltaBias(totalDelta);

        // Find max gamma strike and calculate concentration
        double maxGammaStrike = findMaxGammaStrike(gammaByStrike);
        double gammaConcentration = calculateGammaConcentration(gammaByStrike, totalGamma);

        // Detect gamma squeeze risk
        boolean gammaSqueezeRisk = detectGammaSqueezeRisk(
            spotPrice, maxGammaStrike, gammaConcentration
        );
        Double gammaSqueezeDistance = maxGammaStrike > 0
            ? Math.abs(spotPrice - maxGammaStrike) / spotPrice * 100
            : null;

        // Determine vega structure
        GreeksPortfolio.VegaStructure vegaStructure = determineVegaStructure(nearTermVega, farTermVega);

        // Calculate theta decay estimates
        double dailyThetaDecay = totalTheta;  // Theta is already daily
        double weekendThetaDecay = totalTheta * 3;  // 3 days over weekend

        // Calculate risk metrics
        double gammaRisk = calculateGammaRisk(totalGamma, spotPrice);
        double vegaRisk = totalVega;  // P&L per 1% IV change
        Double spotMoveFor1PctDelta = totalGamma != 0
            ? Math.abs(totalDelta * 0.01 / totalGamma)
            : null;
        double riskScore = calculateRiskScore(totalDelta, totalGamma, totalVega, spotPrice);

        return GreeksPortfolio.builder()
            // Aggregated Greeks
            .totalDelta(totalDelta)
            .totalGamma(totalGamma)
            .totalVega(totalVega)
            .totalTheta(totalTheta)

            // Delta analysis
            .deltaByStrike(deltaByStrike)
            .deltaWeightedAvgStrike(deltaWeightedAvgStrike)
            .deltaBias(deltaBias)
            .callDeltaExposure(callDeltaExposure)
            .putDeltaExposure(putDeltaExposure)

            // Gamma analysis
            .gammaByStrike(gammaByStrike)
            .maxGammaStrike(maxGammaStrike)
            .gammaConcentration(gammaConcentration)
            .gammaSqueezeRisk(gammaSqueezeRisk)
            .gammaSqueezeDistance(gammaSqueezeDistance)

            // Vega analysis
            .vegaByExpiry(vegaByExpiry)
            .nearTermVega(nearTermVega)
            .farTermVega(farTermVega)
            .vegaStructure(vegaStructure)

            // Theta analysis
            .dailyThetaDecay(dailyThetaDecay)
            .weekendThetaDecay(weekendThetaDecay)
            .thetaByExpiry(thetaByExpiry)

            // Risk metrics
            .spotMoveFor1PctDelta(spotMoveFor1PctDelta)
            .gammaRisk(gammaRisk)
            .vegaRisk(vegaRisk)
            .riskScore(riskScore)

            .build();
    }

    /**
     * Determine delta bias based on total delta
     */
    private GreeksPortfolio.DeltaBias determineDeltaBias(double totalDelta) {
        if (totalDelta > DELTA_NEUTRAL_THRESHOLD) {
            return GreeksPortfolio.DeltaBias.LONG;
        } else if (totalDelta < -DELTA_NEUTRAL_THRESHOLD) {
            return GreeksPortfolio.DeltaBias.SHORT;
        } else {
            return GreeksPortfolio.DeltaBias.NEUTRAL;
        }
    }

    /**
     * Find strike with maximum gamma exposure
     */
    private double findMaxGammaStrike(Map<Double, Double> gammaByStrike) {
        return gammaByStrike.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(0.0);
    }

    /**
     * Calculate gamma concentration in top 3 strikes
     */
    private double calculateGammaConcentration(Map<Double, Double> gammaByStrike, double totalGamma) {
        if (totalGamma <= 0 || gammaByStrike.isEmpty()) {
            return 0.0;
        }

        double top3Gamma = gammaByStrike.values().stream()
            .sorted((a, b) -> Double.compare(b, a))  // Descending
            .limit(3)
            .mapToDouble(Double::doubleValue)
            .sum();

        return top3Gamma / totalGamma;
    }

    /**
     * Detect if gamma squeeze risk exists
     */
    private boolean detectGammaSqueezeRisk(double spotPrice, double maxGammaStrike,
                                           double gammaConcentration) {
        if (maxGammaStrike <= 0 || spotPrice <= 0) {
            return false;
        }

        // Distance from spot to max gamma strike (as %)
        double distancePct = Math.abs(spotPrice - maxGammaStrike) / spotPrice * 100;

        // Gamma squeeze risk if:
        // 1. High gamma concentration (> 60% in top 3 strikes)
        // 2. Spot price within 2% of max gamma strike
        return gammaConcentration > GAMMA_CONCENTRATION_THRESHOLD
            && distancePct < GAMMA_SQUEEZE_DISTANCE_PCT;
    }

    /**
     * Determine vega structure (front vs back heavy)
     */
    private GreeksPortfolio.VegaStructure determineVegaStructure(double nearTermVega, double farTermVega) {
        double totalVega = Math.abs(nearTermVega) + Math.abs(farTermVega);
        if (totalVega == 0) {
            return GreeksPortfolio.VegaStructure.BALANCED;
        }

        double nearRatio = Math.abs(nearTermVega) / totalVega;

        if (nearRatio > 0.6) {
            return GreeksPortfolio.VegaStructure.FRONT_HEAVY;
        } else if (nearRatio < 0.4) {
            return GreeksPortfolio.VegaStructure.BACK_HEAVY;
        } else {
            return GreeksPortfolio.VegaStructure.BALANCED;
        }
    }

    /**
     * Calculate gamma risk in currency terms
     * P&L from 1 point spot move squared
     */
    private double calculateGammaRisk(double totalGamma, double spotPrice) {
        // Gamma P&L ≈ 0.5 × gamma × (move)²
        // For 1% move: 0.5 × gamma × (0.01 × spot)²
        double onePercentMove = spotPrice * 0.01;
        return 0.5 * totalGamma * onePercentMove * onePercentMove;
    }

    /**
     * Calculate overall risk score (0-100)
     */
    private double calculateRiskScore(double totalDelta, double totalGamma,
                                      double totalVega, double spotPrice) {
        double score = 0.0;

        // Delta risk component (0-30)
        double deltaNormalized = Math.min(Math.abs(totalDelta) / 10000, 1.0);
        score += deltaNormalized * 30;

        // Gamma risk component (0-40) - higher weight for gamma
        double gammaNormalized = Math.min(Math.abs(totalGamma) / 5000, 1.0);
        score += gammaNormalized * 40;

        // Vega risk component (0-30)
        double vegaNormalized = Math.min(Math.abs(totalVega) / 50000, 1.0);
        score += vegaNormalized * 30;

        return Math.min(score, 100.0);
    }

    /**
     * Estimate days to expiry from expiry string
     * Format expected: "2025-01-30" or similar
     */
    private int estimateDTE(String expiry) {
        if (expiry == null || expiry.isEmpty()) {
            return 30; // Default to far term if unknown
        }

        try {
            java.time.LocalDate expiryDate;
            // Try ISO format first (2026-01-27)
            if (expiry.contains("-")) {
                expiryDate = java.time.LocalDate.parse(expiry);
            } else {
                // Try NSE format (27 JAN 2026)
                java.time.format.DateTimeFormatter nseFormatter =
                    java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH);
                expiryDate = java.time.LocalDate.parse(expiry, nseFormatter);
            }
            java.time.LocalDate today = java.time.LocalDate.now();
            return (int) java.time.temporal.ChronoUnit.DAYS.between(today, expiryDate);
        } catch (Exception e) {
            log.debug("Failed to parse expiry date: {}", expiry);
            return 30; // Default
        }
    }
}
