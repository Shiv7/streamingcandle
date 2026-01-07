package com.kotsin.consumer.calculator;

import com.kotsin.consumer.config.InstrumentConfig;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VolumeProfileCalculator - Calculates POC, VAH, VAL for volume profile
 *
 * EXTRACTED FROM: EnrichedCandlestick (SRP violation fix)
 *
 * Volume Profile = Distribution of volume at each price level
 * - POC (Point of Control) = Price with maximum volume
 * - VAH (Value Area High) = Upper bound of 70% volume area
 * - VAL (Value Area Low) = Lower bound of 70% volume area
 *
 * Thread-safety: Stateless component, thread-safe
 */
@Component
@Slf4j
public class VolumeProfileCalculator {

    private static final double VALUE_AREA_PERCENTAGE = 0.70;

    private final InstrumentConfig instrumentConfig;

    @Autowired
    public VolumeProfileCalculator(InstrumentConfig instrumentConfig) {
        this.instrumentConfig = instrumentConfig;
    }

    /**
     * Volume Profile State (per candle)
     */
    @Data
    public static class VolumeProfile {
        private Map<Double, Long> volumeAtPrice = new ConcurrentHashMap<>();
        private Double lowestPrice;
        private Double highestPrice;
        private long totalVolume;

        /**
         * Add volume at a price level
         */
        public void addVolume(double price, long volume, double tickSize) {
            if (volume <= 0) return;

            // Round price to tick size
            double rounded = roundToTickSize(price, tickSize);

            volumeAtPrice.merge(rounded, volume, Long::sum);
            totalVolume += volume;

            // Update price range
            if (lowestPrice == null || rounded < lowestPrice) lowestPrice = rounded;
            if (highestPrice == null || rounded > highestPrice) highestPrice = rounded;
        }

        /**
         * Merge another volume profile into this one
         */
        public void merge(VolumeProfile other) {
            if (other == null) return;

            other.volumeAtPrice.forEach((price, vol) ->
                this.volumeAtPrice.merge(price, vol, Long::sum));

            this.totalVolume += other.totalVolume;

            if (other.lowestPrice != null) {
                this.lowestPrice = (this.lowestPrice == null)
                    ? other.lowestPrice
                    : Math.min(this.lowestPrice, other.lowestPrice);
            }

            if (other.highestPrice != null) {
                this.highestPrice = (this.highestPrice == null)
                    ? other.highestPrice
                    : Math.max(this.highestPrice, other.highestPrice);
            }
        }

        /**
         * Round price to tick size using BigDecimal to avoid floating point errors
         */
        private double roundToTickSize(double price, double tickSize) {
            BigDecimal p = BigDecimal.valueOf(price);
            BigDecimal step = BigDecimal.valueOf(tickSize);
            BigDecimal rounded = p.divide(step, 0, RoundingMode.HALF_UP)
                .multiply(step)
                .setScale(2, RoundingMode.HALF_UP);
            return rounded.doubleValue();
        }
    }

    /**
     * Calculate Point of Control (price with maximum volume)
     */
    public Double calculatePOC(VolumeProfile profile) {
        if (profile.volumeAtPrice.isEmpty()) return null;

        return profile.volumeAtPrice.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    /**
     * Calculate Value Area (70% of volume around POC)
     */
    public ValueArea calculateValueArea(VolumeProfile profile) {
        if (profile.volumeAtPrice.isEmpty() || profile.totalVolume == 0) {
            return new ValueArea(null, null, 0L);
        }

        Double poc = calculatePOC(profile);
        if (poc == null) return new ValueArea(null, null, 0L);

        long targetVolume = (long) (profile.totalVolume * VALUE_AREA_PERCENTAGE);

        // Start with POC volume
        long accumulatedVolume = profile.volumeAtPrice.getOrDefault(poc, 0L);

        double vaHigh = poc;
        double vaLow = poc;

        // Sort all prices
        List<Double> sortedPrices = new ArrayList<>(profile.volumeAtPrice.keySet());
        Collections.sort(sortedPrices);

        // Find POC index
        int pocIndex = -1;
        for (int i = 0; i < sortedPrices.size(); i++) {
            if (Math.abs(sortedPrices.get(i) - poc) < 0.0001) {
                pocIndex = i;
                break;
            }
        }

        if (pocIndex == -1) {
            return new ValueArea(poc, poc, accumulatedVolume);
        }

        // Expand value area by alternating between price levels above and below POC
        int aboveIndex = pocIndex + 1;
        int belowIndex = pocIndex - 1;

        while (accumulatedVolume < targetVolume && (aboveIndex < sortedPrices.size() || belowIndex >= 0)) {
            long aboveVolume = (aboveIndex < sortedPrices.size()) ?
                profile.volumeAtPrice.getOrDefault(sortedPrices.get(aboveIndex), 0L) : 0L;
            long belowVolume = (belowIndex >= 0) ?
                profile.volumeAtPrice.getOrDefault(sortedPrices.get(belowIndex), 0L) : 0L;

            // Add the price level with higher volume first (standard Volume Profile algorithm)
            if (aboveVolume >= belowVolume && aboveIndex < sortedPrices.size()) {
                accumulatedVolume += aboveVolume;
                vaHigh = sortedPrices.get(aboveIndex);
                aboveIndex++;
            } else if (belowIndex >= 0) {
                accumulatedVolume += belowVolume;
                vaLow = sortedPrices.get(belowIndex);
                belowIndex--;
            } else {
                break;
            }
        }

        return new ValueArea(vaHigh, vaLow, accumulatedVolume);
    }

    /**
     * Value Area result
     */
    @Data
    public static class ValueArea {
        private final Double high;
        private final Double low;
        private final Long volume;

        public ValueArea(Double high, Double low, Long volume) {
            this.high = high;
            this.low = low;
            this.volume = volume;
        }

        /**
         * Check if value area is valid
         */
        public boolean isValid() {
            return high != null && low != null && volume != null && volume > 0;
        }

        /**
         * Get value area range (high - low)
         */
        public double getRange() {
            return (high != null && low != null) ? (high - low) : 0.0;
        }
    }

    /**
     * Rebin volume profile to a new tick size
     * (useful when aggregating candles from different timeframes)
     */
    public VolumeProfile rebinToTickSize(VolumeProfile profile, double newTickSize) {
        if (newTickSize <= 0 || profile.volumeAtPrice.isEmpty()) {
            return profile;
        }

        VolumeProfile rebinned = new VolumeProfile();

        for (Map.Entry<Double, Long> entry : profile.volumeAtPrice.entrySet()) {
            double price = entry.getKey();
            long volume = entry.getValue();
            rebinned.addVolume(price, volume, newTickSize);
        }

        return rebinned;
    }
}
