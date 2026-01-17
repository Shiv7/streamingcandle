package com.kotsin.consumer.enrichment.calculator;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.OptionCandle;
import com.kotsin.consumer.enrichment.model.GEXProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * GEXCalculator - Gamma Exposure Index Calculator for market regime detection.
 *
 * GEX (Gamma Exposure) measures net gamma held by market makers (dealers).
 *
 * KEY ASSUMPTION: Dealers are NET SHORT options (sold to retail/institutional).
 * When dealers are short gamma, they must:
 * - BUY when price rises (delta hedge)
 * - SELL when price falls (delta hedge)
 * This AMPLIFIES moves → TRENDING market
 *
 * When dealers are long gamma:
 * - SELL when price rises
 * - BUY when price falls
 * This DAMPENS moves → MEAN-REVERTING market
 *
 * GEX Calculation per strike:
 *   call_gex = call_gamma × call_oi × spot × 100 (multiplier)
 *   put_gex = put_gamma × put_oi × spot × 100
 *   net_gex = call_gex - put_gex
 *   (Calls and puts have opposite dealer positions typically)
 *
 * Total GEX:
 *   Σ(net_gex) across all strikes
 *
 * INTERPRETATION:
 * - GEX < 0: Dealers SHORT gamma → TRENDING market → Breakouts RUN
 * - GEX > 0: Dealers LONG gamma → MEAN-REVERTING market → Breakouts FAIL
 *
 * USAGE FOR SIGNALS:
 * - Negative GEX: Boost momentum/breakout signals, reduce fade signals
 * - Positive GEX: Boost mean-reversion signals, reduce momentum signals
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GEXCalculator {

    // Contract multiplier (standard lot size for most Indian options)
    private static final int DEFAULT_MULTIPLIER = 100;

    // GEX thresholds for regime classification
    private static final double GEX_TRENDING_THRESHOLD = -1_000_000;
    private static final double GEX_MEAN_REVERTING_THRESHOLD = 1_000_000;

    /**
     * Calculate GEX profile for a FamilyCandle
     *
     * @param family FamilyCandle with options data
     * @return GEXProfile with regime and detailed analysis
     */
    public GEXProfile calculate(FamilyCandle family) {
        if (family == null || family.getOptions() == null || family.getOptions().isEmpty()) {
            return GEXProfile.empty();
        }

        double spotPrice = family.getPrimaryPrice();
        if (spotPrice <= 0) {
            return GEXProfile.empty();
        }

        List<OptionCandle> options = family.getOptions();

        // Group options by strike
        Map<Double, OptionCandle> callsByStrike = new HashMap<>();
        Map<Double, OptionCandle> putsByStrike = new HashMap<>();

        for (OptionCandle opt : options) {
            if (opt == null || opt.getStrikePrice() <= 0) continue;
            if (opt.isCall()) {
                callsByStrike.put(opt.getStrikePrice(), opt);
            } else {
                putsByStrike.put(opt.getStrikePrice(), opt);
            }
        }

        // Calculate GEX per strike
        Map<Double, Double> gexByStrike = new TreeMap<>();
        Map<Double, Double> callGexByStrike = new TreeMap<>();
        Map<Double, Double> putGexByStrike = new TreeMap<>();

        double totalGex = 0;
        double totalCallGex = 0;
        double totalPutGex = 0;
        double maxGexStrike = 0;
        double maxGexValue = 0;
        double minGexStrike = 0;
        double minGexValue = 0;

        // Get all strikes
        Set<Double> allStrikes = new TreeSet<>();
        allStrikes.addAll(callsByStrike.keySet());
        allStrikes.addAll(putsByStrike.keySet());

        for (Double strike : allStrikes) {
            double strikeGex = 0;
            double callGex = 0;
            double putGex = 0;

            // Call GEX: positive (dealers typically short calls, hedge by buying)
            OptionCandle call = callsByStrike.get(strike);
            if (call != null && call.getGamma() != null && call.getOpenInterest() > 0) {
                callGex = call.getGamma() * call.getOpenInterest() * spotPrice * DEFAULT_MULTIPLIER;
                callGexByStrike.put(strike, callGex);
                totalCallGex += callGex;
            }

            // Put GEX: negative (dealers typically short puts, hedge by selling)
            // Note: We use negative sign because put gamma hedging works opposite to calls
            OptionCandle put = putsByStrike.get(strike);
            if (put != null && put.getGamma() != null && put.getOpenInterest() > 0) {
                putGex = -put.getGamma() * put.getOpenInterest() * spotPrice * DEFAULT_MULTIPLIER;
                putGexByStrike.put(strike, putGex);
                totalPutGex += putGex;
            }

            // Net GEX at this strike
            strikeGex = callGex + putGex;
            if (strikeGex != 0) {
                gexByStrike.put(strike, strikeGex);
                totalGex += strikeGex;

                // Track max/min GEX strikes
                if (strikeGex > maxGexValue) {
                    maxGexValue = strikeGex;
                    maxGexStrike = strike;
                }
                if (strikeGex < minGexValue) {
                    minGexValue = strikeGex;
                    minGexStrike = strike;
                }
            }
        }

        // Determine GEX regime
        GEXProfile.GEXRegime regime = determineRegime(totalGex);

        // Find gamma flip point (where GEX changes sign)
        Double gammaFlipLevel = findGammaFlipLevel(gexByStrike, spotPrice);

        // Calculate GEX gradient (how fast GEX changes with price)
        double gexGradient = calculateGEXGradient(gexByStrike, spotPrice);

        // Find key levels
        List<Double> keyResistanceLevels = findKeyLevels(gexByStrike, spotPrice, true);
        List<Double> keySupportLevels = findKeyLevels(gexByStrike, spotPrice, false);

        // Calculate distance to flip
        Double distanceToFlip = gammaFlipLevel != null ?
                (gammaFlipLevel - spotPrice) / spotPrice * 100 : null;

        return GEXProfile.builder()
                .hasOptionsData(true)  // FIX: Mark that we have actual options data
                .familyId(family.getFamilyId())
                .timeframe(family.getTimeframe())
                .spotPrice(spotPrice)
                .totalGex(totalGex)
                .totalCallGex(totalCallGex)
                .totalPutGex(totalPutGex)
                .regime(regime)
                .gexByStrike(gexByStrike)
                .callGexByStrike(callGexByStrike)
                .putGexByStrike(putGexByStrike)
                .maxGexStrike(maxGexStrike)
                .maxGexValue(maxGexValue)
                .minGexStrike(minGexStrike)
                .minGexValue(minGexValue)
                .gammaFlipLevel(gammaFlipLevel)
                .distanceToFlipPct(distanceToFlip)
                .gexGradient(gexGradient)
                .keyResistanceLevels(keyResistanceLevels)
                .keySupportLevels(keySupportLevels)
                .build();
    }

    /**
     * Determine GEX regime
     */
    private GEXProfile.GEXRegime determineRegime(double totalGex) {
        if (totalGex < GEX_TRENDING_THRESHOLD) {
            return GEXProfile.GEXRegime.STRONG_TRENDING;
        } else if (totalGex < 0) {
            return GEXProfile.GEXRegime.TRENDING;
        } else if (totalGex > GEX_MEAN_REVERTING_THRESHOLD) {
            return GEXProfile.GEXRegime.STRONG_MEAN_REVERTING;
        } else if (totalGex > 0) {
            return GEXProfile.GEXRegime.MEAN_REVERTING;
        } else {
            return GEXProfile.GEXRegime.NEUTRAL;
        }
    }

    /**
     * Find the gamma flip level (where net GEX changes sign)
     */
    private Double findGammaFlipLevel(Map<Double, Double> gexByStrike, double spotPrice) {
        if (gexByStrike.isEmpty()) return null;

        List<Double> strikes = new ArrayList<>(gexByStrike.keySet());
        Collections.sort(strikes);

        // Find where GEX changes sign
        for (int i = 0; i < strikes.size() - 1; i++) {
            double strike1 = strikes.get(i);
            double strike2 = strikes.get(i + 1);
            double gex1 = gexByStrike.getOrDefault(strike1, 0.0);
            double gex2 = gexByStrike.getOrDefault(strike2, 0.0);

            // If sign changes between these strikes
            if ((gex1 > 0 && gex2 < 0) || (gex1 < 0 && gex2 > 0)) {
                // Linear interpolation to find flip point
                double flipLevel = strike1 + (strike2 - strike1) * Math.abs(gex1) / (Math.abs(gex1) + Math.abs(gex2));
                return flipLevel;
            }
        }

        return null;
    }

    /**
     * Calculate GEX gradient (rate of change with price)
     * Higher gradient = more volatile response to price moves
     */
    private double calculateGEXGradient(Map<Double, Double> gexByStrike, double spotPrice) {
        if (gexByStrike.size() < 2) return 0;

        // Find strikes around spot price
        List<Double> strikes = new ArrayList<>(gexByStrike.keySet());
        Collections.sort(strikes);

        Double lowerStrike = null;
        Double upperStrike = null;

        for (Double strike : strikes) {
            if (strike < spotPrice) {
                lowerStrike = strike;
            } else if (strike >= spotPrice && upperStrike == null) {
                upperStrike = strike;
            }
        }

        if (lowerStrike == null || upperStrike == null) return 0;

        double lowerGex = gexByStrike.getOrDefault(lowerStrike, 0.0);
        double upperGex = gexByStrike.getOrDefault(upperStrike, 0.0);

        // Gradient = change in GEX per point of price
        return (upperGex - lowerGex) / (upperStrike - lowerStrike);
    }

    /**
     * Find key support/resistance levels based on GEX concentration
     */
    private List<Double> findKeyLevels(Map<Double, Double> gexByStrike, double spotPrice, boolean resistance) {
        List<Double> levels = new ArrayList<>();

        for (Map.Entry<Double, Double> entry : gexByStrike.entrySet()) {
            double strike = entry.getKey();
            double gex = entry.getValue();

            if (resistance) {
                // Resistance: high positive GEX above spot
                if (strike > spotPrice && gex > 0) {
                    levels.add(strike);
                }
            } else {
                // Support: high positive GEX below spot (dealers buy on dips)
                if (strike < spotPrice && gex > 0) {
                    levels.add(strike);
                }
            }
        }

        // Sort by distance from spot
        levels.sort(Comparator.comparingDouble(s -> Math.abs(s - spotPrice)));

        // Return top 3
        return levels.size() > 3 ? levels.subList(0, 3) : levels;
    }
}
