package com.kotsin.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VolumeProfileData {
    // Point of Control (POC)
    private Double poc;                 // Price with maximum volume
    private Long pocVolume;             // Volume at POC
    private Double pocVolumePercent;    // POC volume as % of total

    // Value Area (typically 70% of total volume)
    private Double valueAreaHigh;       // Upper bound
    private Double valueAreaLow;        // Lower bound
    private Long valueAreaVolume;       // Volume within value area
    private Double valueAreaVolumePercent; // Value area volume as % of total

    // Stats
    private Long totalVolume;           // Total volume in window
    private Double priceRange;          // High - Low based on observed prices
    private Double avgPrice;            // Volume-weighted average price across distribution
    private Double volumeStdDev;        // Std dev of price distribution (weighted by volume)

    // Distribution
    private Map<Double, Long> volumeAtPrice;       // Price -> volume
    private Map<Double, Double> volumePercentages; // Price -> % of total

    // Factory for empty profile
    public static VolumeProfileData empty() {
        return VolumeProfileData.builder()
            .poc(null)
            .pocVolume(0L)
            .pocVolumePercent(0.0)
            .valueAreaHigh(null)
            .valueAreaLow(null)
            .valueAreaVolume(0L)
            .valueAreaVolumePercent(0.0)
            .totalVolume(0L)
            .priceRange(0.0)
            .avgPrice(0.0)
            .volumeStdDev(0.0)
            .volumeAtPrice(new HashMap<>())
            .volumePercentages(new HashMap<>())
            .build();
    }

    // Helpers
    public boolean isValid() {
        return poc != null && pocVolume != null && pocVolume > 0;
    }

    public Double getDistanceFromPOC(double price) {
        if (poc == null || poc == 0.0) return null;
        return Math.abs(price - poc) / poc;
    }

    public boolean isWithinValueArea(double price) {
        if (valueAreaHigh == null || valueAreaLow == null) return false;
        return price >= valueAreaLow && price <= valueAreaHigh;
    }

    public String getPositionRelativeToValueArea(double price) {
        if (valueAreaHigh == null || valueAreaLow == null) return "UNKNOWN";
        if (price > valueAreaHigh) return "ABOVE";
        if (price < valueAreaLow) return "BELOW";
        return "WITHIN";
    }

    public Long getVolumeAtPrice(double price) {
        if (volumeAtPrice == null) return 0L;
        return volumeAtPrice.getOrDefault(price, 0L);
    }

    public Double getVolumePercentAtPrice(double price) {
        if (volumePercentages == null) return 0.0;
        return volumePercentages.getOrDefault(price, 0.0);
    }
}
