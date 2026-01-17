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
 * GEXProfile - Gamma Exposure analysis results
 *
 * Contains detailed GEX analysis for market regime detection and signal filtering.
 *
 * Key metrics:
 * - totalGex: Net gamma exposure (negative = trending, positive = mean-reverting)
 * - regime: Market regime classification
 * - gammaFlipLevel: Price where GEX changes sign
 * - gexGradient: How fast GEX changes with price
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GEXProfile {

    // ======================== IDENTITY ========================

    private String familyId;
    private String timeframe;
    private double spotPrice;

    /**
     * FIX: Flag to indicate if options data was available for GEX calculation.
     * When false, GEX values are defaults/empty (not "no gamma flip").
     * Used by consumers to show "N/A" instead of null when no options data.
     */
    private boolean hasOptionsData;

    // ======================== CORE GEX METRICS ========================

    /**
     * Total net GEX across all strikes
     * Negative = dealers short gamma = TRENDING market
     * Positive = dealers long gamma = MEAN-REVERTING market
     */
    private double totalGex;

    /**
     * Total call GEX (positive contribution)
     */
    private double totalCallGex;

    /**
     * Total put GEX (typically negative contribution)
     */
    private double totalPutGex;

    /**
     * Market regime based on GEX
     */
    private GEXRegime regime;

    // ======================== GEX BY STRIKE ========================

    /**
     * Net GEX at each strike
     */
    private Map<Double, Double> gexByStrike;

    /**
     * Call GEX at each strike
     */
    private Map<Double, Double> callGexByStrike;

    /**
     * Put GEX at each strike
     */
    private Map<Double, Double> putGexByStrike;

    // ======================== KEY LEVELS ========================

    /**
     * Strike with maximum positive GEX (resistance)
     */
    private double maxGexStrike;
    private double maxGexValue;

    /**
     * Strike with minimum (most negative) GEX
     */
    private double minGexStrike;
    private double minGexValue;

    /**
     * Price level where GEX changes sign
     * Critical level: behavior changes dramatically when price crosses this
     */
    private Double gammaFlipLevel;

    /**
     * Distance to gamma flip level (% from spot)
     */
    private Double distanceToFlipPct;

    // ======================== GRADIENTS & DYNAMICS ========================

    /**
     * GEX gradient (rate of change per point)
     * High gradient = volatile response to price moves
     */
    private double gexGradient;

    /**
     * Key resistance levels (high positive GEX above spot)
     */
    private List<Double> keyResistanceLevels;

    /**
     * Key support levels (high positive GEX below spot)
     */
    private List<Double> keySupportLevels;

    // ======================== ENUMS ========================

    public enum GEXRegime {
        /**
         * Very negative GEX - Strong trending behavior expected
         * Breakouts will run, don't fade moves
         */
        STRONG_TRENDING,

        /**
         * Negative GEX - Trending behavior
         * Prefer momentum strategies
         */
        TRENDING,

        /**
         * Near-zero GEX - Mixed behavior
         */
        NEUTRAL,

        /**
         * Positive GEX - Mean reverting behavior
         * Prefer fade strategies
         */
        MEAN_REVERTING,

        /**
         * Very positive GEX - Strong mean reversion
         * Breakouts likely to fail
         */
        STRONG_MEAN_REVERTING
    }

    // ======================== HELPER METHODS ========================

    /**
     * Check if market is in trending regime
     */
    public boolean isTrending() {
        return regime == GEXRegime.TRENDING || regime == GEXRegime.STRONG_TRENDING;
    }

    /**
     * Check if market is mean-reverting
     */
    public boolean isMeanReverting() {
        return regime == GEXRegime.MEAN_REVERTING || regime == GEXRegime.STRONG_MEAN_REVERTING;
    }

    /**
     * Get signal modifier based on GEX regime
     *
     * For momentum/breakout signals:
     * - Trending: boost (1.2)
     * - Mean reverting: reduce (0.7)
     *
     * For mean-reversion/fade signals:
     * - Mean reverting: boost (1.2)
     * - Trending: reduce (0.7)
     *
     * @param isMomentumSignal true for momentum/breakout, false for fade/mean-reversion
     * @return Multiplier for signal confidence
     */
    public double getSignalModifier(boolean isMomentumSignal) {
        if (regime == null) return 1.0;

        if (isMomentumSignal) {
            return switch (regime) {
                case STRONG_TRENDING -> 1.3;
                case TRENDING -> 1.2;
                case NEUTRAL -> 1.0;
                case MEAN_REVERTING -> 0.8;
                case STRONG_MEAN_REVERTING -> 0.7;
            };
        } else {
            return switch (regime) {
                case STRONG_TRENDING -> 0.7;
                case TRENDING -> 0.8;
                case NEUTRAL -> 1.0;
                case MEAN_REVERTING -> 1.2;
                case STRONG_MEAN_REVERTING -> 1.3;
            };
        }
    }

    /**
     * Check if near gamma flip level (within 1%)
     */
    public boolean isNearGammaFlip() {
        return distanceToFlipPct != null && Math.abs(distanceToFlipPct) < 1.0;
    }

    /**
     * Get nearest key level (support or resistance)
     */
    public Double getNearestKeyLevel() {
        Double nearestSupport = keySupportLevels != null && !keySupportLevels.isEmpty() ?
                keySupportLevels.get(0) : null;
        Double nearestResistance = keyResistanceLevels != null && !keyResistanceLevels.isEmpty() ?
                keyResistanceLevels.get(0) : null;

        if (nearestSupport == null) return nearestResistance;
        if (nearestResistance == null) return nearestSupport;

        double distToSupport = Math.abs(spotPrice - nearestSupport);
        double distToResistance = Math.abs(spotPrice - nearestResistance);

        return distToSupport < distToResistance ? nearestSupport : nearestResistance;
    }

    /**
     * Get regime description
     */
    public String getRegimeDescription() {
        if (regime == null) return "Unknown";

        return switch (regime) {
            case STRONG_TRENDING -> "Strong Trending (dealers short gamma) - Breakouts will RUN";
            case TRENDING -> "Trending - Prefer momentum strategies";
            case NEUTRAL -> "Neutral - Mixed behavior expected";
            case MEAN_REVERTING -> "Mean Reverting - Prefer fade strategies";
            case STRONG_MEAN_REVERTING -> "Strong Mean Reverting - Breakouts will FAIL";
        };
    }

    /**
     * Factory method for empty profile (no options data available)
     */
    public static GEXProfile empty() {
        return GEXProfile.builder()
                .hasOptionsData(false)  // FIX: Indicate no options data
                .totalGex(0)
                .totalCallGex(0)
                .totalPutGex(0)
                .regime(GEXRegime.NEUTRAL)
                .gexByStrike(new TreeMap<>())
                .callGexByStrike(new TreeMap<>())
                .putGexByStrike(new TreeMap<>())
                .keyResistanceLevels(Collections.emptyList())
                .keySupportLevels(Collections.emptyList())
                .build();
    }

    /**
     * Check if this profile has actual options data
     */
    public boolean hasData() {
        return hasOptionsData;
    }
}
