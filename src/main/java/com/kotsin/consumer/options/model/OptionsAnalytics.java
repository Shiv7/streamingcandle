package com.kotsin.consumer.options.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * OptionsAnalytics - Chain-level options analytics.
 *
 * Contains:
 * - Max Pain analysis
 * - GEX (Gamma Exposure) profile
 * - PCR (Put-Call Ratio)
 * - IV Surface analysis
 * - OI distribution
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptionsAnalytics {

    private String underlyingSymbol;
    private double spotPrice;
    private Instant timestamp;

    // ==================== MAX PAIN ====================
    private double maxPain;              // Strike with minimum pain for writers
    private double maxPainDistance;      // Distance from spot as percentage
    private double totalPainAtMaxPain;   // Total pain value at max pain strike

    // ==================== PCR (Put-Call Ratio) ====================
    private double pcrByOI;              // PCR by Open Interest
    private double pcrByVolume;          // PCR by Volume
    private double pcrByPremium;         // PCR by Premium value
    private String pcrSignal;            // BULLISH, BEARISH, NEUTRAL, EXTREME_*

    // ==================== GEX (Gamma Exposure) ====================
    private double totalGEX;             // Total Gamma Exposure
    private double gexFlipPoint;         // Strike where GEX changes sign
    private Map<Double, Double> gexProfile;  // GEX by strike
    private boolean isPositiveGamma;     // Dealers are long gamma

    // ==================== IV ANALYSIS ====================
    private double atmIV;                // ATM Implied Volatility
    private double ivSkew;               // OTM Put IV - OTM Call IV
    private double ivSmile;              // Wing IV - ATM IV
    private double ivPercentile;         // IV rank (0-100)
    private String ivTerm;               // Term structure: CONTANGO, BACKWARDATION, FLAT

    // ==================== OI ANALYSIS ====================
    private double maxCallOIStrike;      // Strike with max Call OI
    private double maxPutOIStrike;       // Strike with max Put OI
    private double callOIWall;           // Resistance from Call OI
    private double putOIWall;            // Support from Put OI
    private double[] oiBasedRange;       // Expected range from OI

    // ==================== DERIVED SIGNALS ====================
    private boolean bullishSignal;       // Options data suggests bullish
    private boolean bearishSignal;       // Options data suggests bearish
    private boolean rangebound;          // Price likely to stay near max pain

    // ==================== HELPER METHODS ====================

    public boolean isHighIV() {
        return ivPercentile > 70;
    }

    public boolean isLowIV() {
        return ivPercentile < 30;
    }

    public boolean hasNegativeSkew() {
        return ivSkew > 2;  // Puts more expensive than calls
    }

    public boolean isNearMaxPain() {
        return Math.abs(maxPainDistance) < 0.01;  // Within 1%
    }

    public double getExpectedRange() {
        if (oiBasedRange != null && oiBasedRange.length == 2) {
            return oiBasedRange[1] - oiBasedRange[0];
        }
        return 0;
    }

    public String getMarketSentiment() {
        if (pcrByOI > 1.5 && ivSkew > 3) return "EXTREME_FEAR";
        if (pcrByOI > 1.2) return "BEARISH";
        if (pcrByOI < 0.5) return "EXTREME_GREED";
        if (pcrByOI < 0.8) return "BULLISH";
        return "NEUTRAL";
    }
}
