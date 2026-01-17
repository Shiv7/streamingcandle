package com.kotsin.consumer.enrichment.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * MaxPainProfile - Options Max Pain analysis results
 *
 * Max Pain is the price at which option writers have minimum payout.
 * Price tends to gravitate towards max pain near expiry.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaxPainProfile {

    // ======================== IDENTITY ========================

    private String familyId;
    private String timeframe;
    private double spotPrice;

    /**
     * FIX: Flag to indicate if options data was available for max pain calculation.
     * When false, values are defaults/empty (not "no max pain").
     * Used by consumers to show "N/A" instead of 0 when no options data.
     */
    private boolean hasOptionsData;

    // ======================== MAX PAIN METRICS ========================

    /**
     * The strike price where total pain is minimized
     */
    private double maxPainStrike;

    /**
     * Total pain (payout to option buyers) at max pain level
     */
    private double totalPainAtMaxPain;

    /**
     * Distance from spot to max pain as percentage
     * Positive = max pain above spot, negative = below
     */
    private double distanceToMaxPainPct;

    /**
     * Absolute distance (for threshold checks)
     */
    private double absDistancePct;

    /**
     * Max pain bias for trading
     */
    private MaxPainBias bias;

    // ======================== PAIN DISTRIBUTION ========================

    /**
     * Total pain at each potential settlement price
     */
    private Map<Double, Double> painBySettlement;

    /**
     * Call OI distribution by strike
     */
    private Map<Double, Long> callOIByStrike;

    /**
     * Put OI distribution by strike
     */
    private Map<Double, Long> putOIByStrike;

    // ======================== PIN ZONE ========================

    /**
     * Upper bound of pin zone (max pain + 0.5%)
     */
    private Double pinZoneHigh;

    /**
     * Lower bound of pin zone (max pain - 0.5%)
     */
    private Double pinZoneLow;

    /**
     * Is spot currently in the pin zone?
     */
    private boolean isInPinZone;

    // ======================== SECONDARY LEVELS ========================

    /**
     * Other local pain minima (secondary magnets)
     */
    private List<Double> secondaryPainLevels;

    /**
     * Strength of max pain effect (0-1)
     * Higher = more OI concentrated around max pain
     */
    private double maxPainStrength;

    // ======================== ENUMS ========================

    public enum MaxPainBias {
        /**
         * Price significantly above max pain - expect pullback
         */
        BEARISH,

        /**
         * Price significantly below max pain - expect bounce
         */
        BULLISH,

        /**
         * Price in pin zone - expect range-bound
         */
        PIN_EXPECTED,

        /**
         * Price near max pain but not in pin zone
         */
        NEUTRAL
    }

    // ======================== HELPER METHODS ========================

    /**
     * Get signal modifier for expiry day
     *
     * On expiry day afternoon:
     * - If bullish bias (price below max pain): boost long signals
     * - If bearish bias (price above max pain): boost short signals
     * - If pin expected: reduce all directional signals
     *
     * @param isLongSignal true for long signals, false for short
     * @param isExpiryDay true if this is expiry day
     * @param isAfternoon true if after 12:00
     * @return Confidence modifier
     */
    public double getSignalModifier(boolean isLongSignal, boolean isExpiryDay, boolean isAfternoon) {
        if (!isExpiryDay || !isAfternoon) {
            // Max pain effect weak before expiry day afternoon
            return 1.0;
        }

        if (bias == null) return 1.0;

        return switch (bias) {
            case BULLISH -> isLongSignal ? 1.3 : 0.7;
            case BEARISH -> isLongSignal ? 0.7 : 1.3;
            case PIN_EXPECTED -> 0.5; // Reduce all directional signals
            case NEUTRAL -> 1.0;
        };
    }

    /**
     * Get expected move direction towards max pain
     * @return +1 for up, -1 for down, 0 for neutral/pin
     */
    public int getExpectedDirection() {
        if (bias == MaxPainBias.BULLISH) return 1;
        if (bias == MaxPainBias.BEARISH) return -1;
        return 0;
    }

    /**
     * Get target price (max pain level)
     */
    public double getTargetPrice() {
        return maxPainStrike;
    }

    /**
     * Check if max pain is actionable (significant distance)
     */
    public boolean isActionable() {
        return absDistancePct >= 1.5 && maxPainStrength >= 0.3;
    }

    /**
     * Get bias description
     */
    public String getBiasDescription() {
        if (bias == null) return "Unknown";

        return switch (bias) {
            case BULLISH -> String.format("Price %.2f%% below max pain %.0f - expect bounce",
                    absDistancePct, maxPainStrike);
            case BEARISH -> String.format("Price %.2f%% above max pain %.0f - expect pullback",
                    absDistancePct, maxPainStrike);
            case PIN_EXPECTED -> String.format("Price in pin zone around %.0f - expect range-bound",
                    maxPainStrike);
            case NEUTRAL -> String.format("Price near max pain %.0f - neutral bias", maxPainStrike);
        };
    }

    /**
     * Factory method for empty profile (no options data available)
     */
    public static MaxPainProfile empty() {
        return MaxPainProfile.builder()
                .hasOptionsData(false)  // FIX: Indicate no options data
                .maxPainStrike(0)
                .totalPainAtMaxPain(0)
                .distanceToMaxPainPct(0)
                .absDistancePct(0)
                .bias(MaxPainBias.NEUTRAL)
                .painBySettlement(new TreeMap<>())
                .callOIByStrike(new TreeMap<>())
                .putOIByStrike(new TreeMap<>())
                .isInPinZone(false)
                .secondaryPainLevels(Collections.emptyList())
                .maxPainStrength(0)
                .build();
    }

    /**
     * Check if this profile has actual options data
     */
    public boolean hasData() {
        return hasOptionsData;
    }
}
