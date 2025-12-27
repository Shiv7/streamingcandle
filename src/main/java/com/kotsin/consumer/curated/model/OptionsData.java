package com.kotsin.consumer.curated.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OptionsData - Options chain data for a scrip
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptionsData {

    private String scripCode;
    private double spotPrice;

    // ATM (At-The-Money) strike
    private double atmStrike;

    // Total OI across all strikes
    private long totalCallOI;
    private long totalPutOI;
    private double pcr;                 // Put-Call Ratio = putOI / callOI

    // ATM strike specific
    private long atmCallOI;
    private long atmPutOI;
    private double atmCallIV;           // Implied Volatility
    private double atmPutIV;

    // OI changes (indicate fresh positions)
    private long totalCallOIChange;
    private long totalPutOIChange;
    private long atmCallOIChange;
    private long atmPutOIChange;

    // Max Pain (strike with max OI)
    private double maxPainStrike;

    // OI distribution
    private double callOIConcentration;  // % of call OI in ATM ± 2 strikes
    private double putOIConcentration;   // % of put OI in ATM ± 2 strikes

    // Data freshness
    private long timestamp;
    private boolean isStale;

    /**
     * Interpret PCR for sentiment
     */
    public Sentiment getSentiment() {
        if (pcr < 0.7) return Sentiment.BULLISH;      // More calls than puts
        if (pcr > 1.3) return Sentiment.BEARISH;      // More puts than calls
        return Sentiment.NEUTRAL;
    }

    public enum Sentiment {
        BULLISH,    // PCR < 0.7
        NEUTRAL,    // 0.7 <= PCR <= 1.3
        BEARISH     // PCR > 1.3
    }

    /**
     * Check if data is fresh (< 5 minutes old)
     */
    public boolean isFresh() {
        if (timestamp == 0) return false;
        long ageMillis = System.currentTimeMillis() - timestamp;
        return ageMillis < (5 * 60 * 1000);
    }

    /**
     * Check if options indicate strong directional move
     */
    public boolean indicatesStrongMove() {
        // High OI change in one direction indicates strong positioning
        long netOIChange = totalCallOIChange - totalPutOIChange;
        long totalOIChange = Math.abs(totalCallOIChange) + Math.abs(totalPutOIChange);

        if (totalOIChange == 0) return false;

        double changeRatio = Math.abs((double) netOIChange / totalOIChange);
        return changeRatio > 0.6;  // > 60% skew in one direction
    }

    /**
     * Check if price is near max pain (could be pinned)
     */
    public boolean isNearMaxPain() {
        if (maxPainStrike == 0 || spotPrice == 0) return false;
        double distancePercent = Math.abs((spotPrice - maxPainStrike) / spotPrice) * 100;
        return distancePercent < 2.0;  // Within 2% of max pain
    }
}
