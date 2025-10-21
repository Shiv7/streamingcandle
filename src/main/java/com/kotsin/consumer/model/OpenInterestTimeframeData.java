package com.kotsin.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Open Interest data for a specific timeframe
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenInterestTimeframeData {
    
    private Long oi;
    private Long oiChange;
    private Double oiChangePercent;
    private Double oiMomentum;
    private Double oiConcentration;          // HHI index of OI distribution
    private Boolean isComplete;
    private Long windowStart;
    private Long windowEnd;

    // Put/Call analytics (NEW - for options)
    private Long putOi;                      // Total Put OI
    private Long callOi;                     // Total Call OI
    private Double putCallRatio;             // putOi / callOi
    private Long putOiChange;                // Put OI delta in window
    private Long callOiChange;               // Call OI delta in window
    private Double putCallRatioChange;       // Change in put/call ratio

    // OI vs Volume correlation (NEW)
    private Long volumeInWindow;             // Total volume during this OI window
    private Double oiVolumeCorrelation;      // Correlation between OI change and volume
    
    /**
     * Calculate OI momentum (change per minute)
     */
    public Double getOiMomentumPerMinute() {
        if (oiMomentum == null || windowStart == null || windowEnd == null) return null;
        long windowMinutes = (windowEnd - windowStart) / (60 * 1000);
        return windowMinutes > 0 ? oiMomentum / windowMinutes : null;
    }
    
    /**
     * Check if OI is increasing
     */
    public Boolean isOiIncreasing() {
        return oiChange != null && oiChange > 0;
    }
    
    /**
     * Check if OI is decreasing
     */
    public Boolean isOiDecreasing() {
        return oiChange != null && oiChange < 0;
    }
    
    /**
     * Get OI change strength (absolute value)
     */
    public Long getOiChangeStrength() {
        return oiChange != null ? Math.abs(oiChange) : null;
    }
    
    /**
     * Check if put/call ratio indicates bearish sentiment (PCR > 1.2)
     */
    public Boolean isBearishSentiment() {
        return putCallRatio != null && putCallRatio > 1.2;
    }

    /**
     * Check if put/call ratio indicates bullish sentiment (PCR < 0.8)
     */
    public Boolean isBullishSentiment() {
        return putCallRatio != null && putCallRatio < 0.8;
    }

    /**
     * Check if OI and volume are correlated (both increasing/decreasing)
     */
    public Boolean isOiVolumeCorrelated() {
        return oiVolumeCorrelation != null && Math.abs(oiVolumeCorrelation) > 0.5;
    }

    /**
     * Check if OI is increasing but volume is low (weak signal)
     */
    public Boolean isOiIncreaseLowVolume() {
        if (oiChange == null || volumeInWindow == null) return null;
        return oiChange > 0 && volumeInWindow < 10000;  // Threshold adjustable
    }

    /**
     * Get display string for logging
     */
    public String getDisplayString() {
        if (putCallRatio != null) {
            return String.format("OI[%d,%+d,%.2f%%] PCR:%.2f Put:%d Call:%d %s",
                oi, oiChange, oiChangePercent, putCallRatio, putOi, callOi,
                isComplete != null && isComplete ? "COMPLETE" : "PARTIAL");
        }
        return String.format("OI[%d,%+d,%.2f%%] %s",
            oi, oiChange, oiChangePercent,
            isComplete != null && isComplete ? "COMPLETE" : "PARTIAL");
    }
}
