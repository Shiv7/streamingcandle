package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.VolumeProfileData;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Volume Profile Accumulator
 * Calculates Point of Control (POC) and Value Area from volume-at-price distribution.
 *
 * Design Pattern: Accumulator
 * Purpose: Track volume distribution across price levels to identify key support/resistance.
 * Thread-Safety: Not thread-safe (intended for single Kafka Streams task usage).
 */
@Data
@Slf4j
public class VolumeProfileAccumulator {

    // Volume distribution: price -> total volume at that price
    private final Map<Double, Long> volumeAtPrice = new HashMap<>();

    // Track total volume for percentage calculations
    private long totalVolume = 0L;

    // Track observed price range
    private Double lowestPrice = null;
    private Double highestPrice = null;

    // Configuration
    private static final double VALUE_AREA_PERCENTAGE = 0.70; // 70% of volume
    private static final double PRICE_TICK_SIZE = 0.05;       // Default tick size for NSE/MCX

    /**
     * Add a trade to the volume profile.
     * @param price  trade price
     * @param volume trade volume (quantity)
     */
    public void addTrade(double price, long volume) {
        if (volume <= 0) return;

        // Round price to tick size for stable aggregation
        double rounded = roundToTickSize(price);

        volumeAtPrice.merge(rounded, volume, Long::sum);
        totalVolume += volume;

        // Update price range
        if (lowestPrice == null || rounded < lowestPrice) lowestPrice = rounded;
        if (highestPrice == null || rounded > highestPrice) highestPrice = rounded;
    }

    /**
     * Compute volume profile metrics snapshot.
     */
    public VolumeProfileData calculate() {
        if (volumeAtPrice.isEmpty() || totalVolume == 0) {
            return VolumeProfileData.empty();
        }

        // POC
        Map.Entry<Double, Long> pocEntry = volumeAtPrice.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .orElse(null);

        if (pocEntry == null) return VolumeProfileData.empty();

        double poc = pocEntry.getKey();
        long pocVolume = pocEntry.getValue();

        // Value Area (70% around POC by proximity)
        ValueAreaResult valueArea = calculateValueArea(poc);

        // Volume percentages per price level
        Map<Double, Double> volumePercentages = calculateVolumePercentages();

        // Stats
        double avgPrice = calculateWeightedAveragePrice();
        double volumeStdDev = calculateVolumeStdDev(avgPrice);

        return VolumeProfileData.builder()
            .poc(poc)
            .pocVolume(pocVolume)
            .pocVolumePercent(pocVolume * 100.0 / totalVolume)
            .valueAreaHigh(valueArea.high)
            .valueAreaLow(valueArea.low)
            .valueAreaVolume(valueArea.volume)
            .valueAreaVolumePercent(valueArea.volume * 100.0 / totalVolume)
            .totalVolume(totalVolume)
            .priceRange((lowestPrice != null && highestPrice != null) ? (highestPrice - lowestPrice) : 0.0)
            .avgPrice(avgPrice)
            .volumeStdDev(volumeStdDev)
            .volumeAtPrice(new HashMap<>(volumeAtPrice))
            .volumePercentages(volumePercentages)
            .build();
    }

    private ValueAreaResult calculateValueArea(double poc) {
        // Sort price levels by proximity to POC
        List<Map.Entry<Double, Long>> sortedByProximityToPOC = volumeAtPrice.entrySet().stream()
            .sorted(Comparator.comparingDouble(e -> Math.abs(e.getKey() - poc)))
            .collect(Collectors.toList());

        long targetVolume = (long) (totalVolume * VALUE_AREA_PERCENTAGE);
        long accumulatedVolume = 0L;

        double vaHigh = poc;
        double vaLow = poc;

        for (Map.Entry<Double, Long> entry : sortedByProximityToPOC) {
            double price = entry.getKey();
            long vol = entry.getValue();
            accumulatedVolume += vol;
            if (price > vaHigh) vaHigh = price;
            if (price < vaLow) vaLow = price;
            if (accumulatedVolume >= targetVolume) break;
        }

        return new ValueAreaResult(vaHigh, vaLow, accumulatedVolume);
    }

    private Map<Double, Double> calculateVolumePercentages() {
        Map<Double, Double> pct = new HashMap<>();
        for (Map.Entry<Double, Long> e : volumeAtPrice.entrySet()) {
            pct.put(e.getKey(), (e.getValue() * 100.0) / totalVolume);
        }
        return pct;
    }

    private double calculateWeightedAveragePrice() {
        if (totalVolume == 0) return 0.0;
        double sumPxVol = volumeAtPrice.entrySet().stream()
            .mapToDouble(e -> e.getKey() * e.getValue())
            .sum();
        return sumPxVol / totalVolume;
    }

    private double calculateVolumeStdDev(double avgPrice) {
        if (volumeAtPrice.size() <= 1) return 0.0;
        double sumSq = volumeAtPrice.entrySet().stream()
            .mapToDouble(e -> {
                double d = e.getKey() - avgPrice;
                return e.getValue() * d * d;
            })
            .sum();
        return Math.sqrt(sumSq / totalVolume);
    }

    private double roundToTickSize(double price) {
        return Math.round(price / PRICE_TICK_SIZE) * PRICE_TICK_SIZE;
    }

    /** Reset for a new window. */
    public void reset() {
        volumeAtPrice.clear();
        totalVolume = 0L;
        lowestPrice = null;
        highestPrice = null;
    }

    private static class ValueAreaResult {
        final double high;
        final double low;
        final long volume;
        ValueAreaResult(double high, double low, long volume) {
            this.high = high;
            this.low = low;
            this.volume = volume;
        }
    }
}

