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
    private Double oiConcentration;
    private Boolean isComplete;
    private Long windowStart;
    private Long windowEnd;
    
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
     * Get display string for logging
     */
    public String getDisplayString() {
        return String.format("OI[%d,%+d,%.2f%%] %s", 
            oi, oiChange, oiChangePercent, 
            isComplete ? "COMPLETE" : "PARTIAL");
    }
}
