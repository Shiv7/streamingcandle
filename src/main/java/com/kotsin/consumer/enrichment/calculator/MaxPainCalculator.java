package com.kotsin.consumer.enrichment.calculator;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.OptionCandle;
import com.kotsin.consumer.enrichment.model.MaxPainProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MaxPainCalculator - Calculates Options Max Pain level
 *
 * MAX PAIN THEORY:
 * The price at which option writers (sellers) have the LEAST to pay out.
 * Most retail traders lose money on options, so price tends to gravitate
 * towards max pain near expiry.
 *
 * CALCULATION:
 * For each potential settlement price K:
 *   call_pain = Σ [call_oi × max(0, K - strike)] for all calls
 *   put_pain = Σ [put_oi × max(0, strike - K)] for all puts
 *   total_pain(K) = call_pain + put_pain
 *
 * max_pain_strike = K where total_pain(K) is MINIMUM
 *
 * TRADING IMPLICATIONS:
 * - On expiry day afternoon (after 12:00), expect price to gravitate to max pain
 * - If price > max pain by 1.5%+: BEARISH bias (expect pullback)
 * - If price < max pain by 1.5%+: BULLISH bias (expect bounce)
 * - If price near max pain (<0.5%): Expect PIN (range-bound)
 *
 * CAVEATS:
 * - Works best on expiry day (last 4-6 hours)
 * - Less reliable for far-dated expiries
 * - Can be overridden by strong institutional flow
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MaxPainCalculator {

    // Distance thresholds for max pain signals
    private static final double SIGNIFICANT_DISTANCE_PCT = 1.5;
    private static final double PIN_ZONE_PCT = 0.5;

    /**
     * Calculate Max Pain profile for a FamilyCandle
     *
     * @param family FamilyCandle with options data
     * @return MaxPainProfile with max pain level and analysis
     */
    public MaxPainProfile calculate(FamilyCandle family) {
        if (family == null || family.getOptions() == null || family.getOptions().isEmpty()) {
            return MaxPainProfile.empty();
        }

        double spotPrice = family.getPrimaryPrice();
        if (spotPrice <= 0) {
            return MaxPainProfile.empty();
        }

        List<OptionCandle> options = family.getOptions();

        // Group options by strike
        Map<Double, Long> callOIByStrike = new TreeMap<>();
        Map<Double, Long> putOIByStrike = new TreeMap<>();

        for (OptionCandle opt : options) {
            if (opt == null || opt.getStrikePrice() <= 0 || opt.getOpenInterest() <= 0) continue;

            double strike = opt.getStrikePrice();
            long oi = opt.getOpenInterest();

            if (opt.isCall()) {
                callOIByStrike.merge(strike, oi, Long::sum);
            } else {
                putOIByStrike.merge(strike, oi, Long::sum);
            }
        }

        if (callOIByStrike.isEmpty() && putOIByStrike.isEmpty()) {
            return MaxPainProfile.empty();
        }

        // Get all strikes
        Set<Double> allStrikes = new TreeSet<>();
        allStrikes.addAll(callOIByStrike.keySet());
        allStrikes.addAll(putOIByStrike.keySet());

        // Calculate pain at each potential settlement price
        Map<Double, Double> painBySettlement = new TreeMap<>();
        double minPain = Double.MAX_VALUE;
        double maxPainStrike = 0;

        for (Double settlementPrice : allStrikes) {
            double totalPain = 0;

            // Call pain: for each call strike, if settlement > strike, call buyers profit
            for (Map.Entry<Double, Long> entry : callOIByStrike.entrySet()) {
                double strike = entry.getKey();
                long oi = entry.getValue();
                double intrinsic = Math.max(0, settlementPrice - strike);
                totalPain += intrinsic * oi;
            }

            // Put pain: for each put strike, if settlement < strike, put buyers profit
            for (Map.Entry<Double, Long> entry : putOIByStrike.entrySet()) {
                double strike = entry.getKey();
                long oi = entry.getValue();
                double intrinsic = Math.max(0, strike - settlementPrice);
                totalPain += intrinsic * oi;
            }

            painBySettlement.put(settlementPrice, totalPain);

            if (totalPain < minPain) {
                minPain = totalPain;
                maxPainStrike = settlementPrice;
            }
        }

        // Calculate distance from spot to max pain
        double distanceToMaxPain = (maxPainStrike - spotPrice) / spotPrice * 100;
        double absDistance = Math.abs(distanceToMaxPain);

        // Determine max pain bias
        MaxPainProfile.MaxPainBias bias = determineBias(distanceToMaxPain, absDistance);

        // Calculate "gravitational" ranges
        Double pinZoneHigh = maxPainStrike * (1 + PIN_ZONE_PCT / 100);
        Double pinZoneLow = maxPainStrike * (1 - PIN_ZONE_PCT / 100);

        // Find secondary pain levels (local minima)
        List<Double> secondaryPainLevels = findSecondaryPainLevels(painBySettlement, maxPainStrike);

        // Calculate max pain strength (how concentrated is the OI around max pain)
        double maxPainStrength = calculateMaxPainStrength(callOIByStrike, putOIByStrike, maxPainStrike);

        return MaxPainProfile.builder()
                .hasOptionsData(true)  // FIX: Mark that we have actual options data
                .familyId(family.getFamilyId())
                .timeframe(family.getTimeframe())
                .spotPrice(spotPrice)
                .maxPainStrike(maxPainStrike)
                .totalPainAtMaxPain(minPain)
                .distanceToMaxPainPct(distanceToMaxPain)
                .absDistancePct(absDistance)
                .bias(bias)
                .painBySettlement(painBySettlement)
                .callOIByStrike(callOIByStrike)
                .putOIByStrike(putOIByStrike)
                .pinZoneHigh(pinZoneHigh)
                .pinZoneLow(pinZoneLow)
                .isInPinZone(absDistance < PIN_ZONE_PCT)
                .secondaryPainLevels(secondaryPainLevels)
                .maxPainStrength(maxPainStrength)
                .build();
    }

    /**
     * Determine max pain bias based on distance
     */
    private MaxPainProfile.MaxPainBias determineBias(double distance, double absDistance) {
        if (absDistance < PIN_ZONE_PCT) {
            return MaxPainProfile.MaxPainBias.PIN_EXPECTED;
        }

        if (absDistance >= SIGNIFICANT_DISTANCE_PCT) {
            // Price significantly away from max pain
            if (distance > 0) {
                // Max pain is above spot - price should rise to max pain
                return MaxPainProfile.MaxPainBias.BULLISH;
            } else {
                // Max pain is below spot - price should fall to max pain
                return MaxPainProfile.MaxPainBias.BEARISH;
            }
        }

        return MaxPainProfile.MaxPainBias.NEUTRAL;
    }

    /**
     * Find secondary pain levels (other local minima)
     */
    private List<Double> findSecondaryPainLevels(Map<Double, Double> painBySettlement, double maxPainStrike) {
        List<Double> secondary = new ArrayList<>();
        List<Double> strikes = new ArrayList<>(painBySettlement.keySet());

        if (strikes.size() < 3) return secondary;

        Collections.sort(strikes);

        // Find local minima (pain lower than both neighbors)
        for (int i = 1; i < strikes.size() - 1; i++) {
            double strike = strikes.get(i);
            double pain = painBySettlement.get(strike);
            double prevPain = painBySettlement.get(strikes.get(i - 1));
            double nextPain = painBySettlement.get(strikes.get(i + 1));

            if (pain < prevPain && pain < nextPain && strike != maxPainStrike) {
                secondary.add(strike);
            }
        }

        // Sort by pain value (lowest first) and take top 3
        secondary.sort(Comparator.comparingDouble(s -> painBySettlement.getOrDefault(s, Double.MAX_VALUE)));
        return secondary.size() > 3 ? secondary.subList(0, 3) : secondary;
    }

    /**
     * Calculate max pain strength (0-1)
     * Higher = more OI concentrated around max pain = stronger magnet
     */
    private double calculateMaxPainStrength(Map<Double, Long> callOI, Map<Double, Long> putOI, double maxPainStrike) {
        long totalCallOI = callOI.values().stream().mapToLong(Long::longValue).sum();
        long totalPutOI = putOI.values().stream().mapToLong(Long::longValue).sum();
        long totalOI = totalCallOI + totalPutOI;

        if (totalOI == 0) return 0;

        // OI at max pain strike
        long oiAtMaxPain = callOI.getOrDefault(maxPainStrike, 0L) + putOI.getOrDefault(maxPainStrike, 0L);

        // OI within 2 strikes of max pain
        long oiNearMaxPain = 0;
        for (Double strike : callOI.keySet()) {
            if (Math.abs(strike - maxPainStrike) / maxPainStrike < 0.02) {
                oiNearMaxPain += callOI.getOrDefault(strike, 0L);
            }
        }
        for (Double strike : putOI.keySet()) {
            if (Math.abs(strike - maxPainStrike) / maxPainStrike < 0.02) {
                oiNearMaxPain += putOI.getOrDefault(strike, 0L);
            }
        }

        // Strength = concentration of OI near max pain
        double concentration = (double) oiNearMaxPain / totalOI;
        return Math.min(1.0, concentration * 2); // Scale to 0-1
    }
}
