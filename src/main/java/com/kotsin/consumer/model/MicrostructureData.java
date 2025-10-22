package com.kotsin.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Microstructure features data
 * Order Flow Imbalance, VPIN, Depth Imbalance, etc.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MicrostructureData {
    
    private Double ofi;                    // Order Flow Imbalance
    private Double vpin;                   // Volume-Synchronized Probability of Informed Trading
    private Double depthImbalance;         // Depth Imbalance
    private Double kyleLambda;             // Kyle's Lambda
    private Double effectiveSpread;        // Effective Spread
    private Double microprice;             // Microprice
    private Double midPrice;               // Mid-price (bid+ask)/2
    private Double bidAskSpread;           // Bid-Ask spread
    private Boolean isComplete;
    private Long windowStart;
    private Long windowEnd;
    
    /**
     * Check if microstructure data is valid
     */
    public boolean isValid() {
        return ofi != null || vpin != null || depthImbalance != null || 
               kyleLambda != null || effectiveSpread != null || microprice != null;
    }
    
    /**
     * Get OFI strength (absolute value)
     */
    public Double getOfiStrength() {
        return ofi != null ? Math.abs(ofi) : null;
    }
    
    /**
     * Check if OFI is positive (buying pressure)
     */
    public Boolean isOfiPositive() {
        return ofi != null && ofi > 0;
    }
    
    /**
     * Check if OFI is negative (selling pressure)
     */
    public Boolean isOfiNegative() {
        return ofi != null && ofi < 0;
    }
    
    /**
     * Get VPIN level (0-1 scale)
     */
    public String getVpinLevel() {
        if (vpin == null) return "UNKNOWN";
        if (vpin < 0.3) return "LOW";
        if (vpin < 0.6) return "MEDIUM";
        if (vpin < 0.8) return "HIGH";
        return "VERY_HIGH";
    }
    
    /**
     * Check if depth is imbalanced (buy side)
     */
    public Boolean isDepthBuyImbalanced() {
        return depthImbalance != null && depthImbalance > 0.1;
    }
    
    /**
     * Check if depth is imbalanced (sell side)
     */
    public Boolean isDepthSellImbalanced() {
        return depthImbalance != null && depthImbalance < -0.1;
    }
    
    /**
     * Get spread level
     */
    public String getSpreadLevel() {
        if (effectiveSpread == null) return "UNKNOWN";
        if (effectiveSpread < 0.01) return "TIGHT";
        if (effectiveSpread < 0.05) return "NORMAL";
        if (effectiveSpread < 0.1) return "WIDE";
        return "VERY_WIDE";
    }
    
    /**
     * Get display string for logging
     */
    public String getDisplayString() {
        return String.format("Micro[OFI:%.2f,VPIN:%.2f,Depth:%.2f,Spread:%.3f] %s",
            ofi != null ? ofi : 0.0,
            vpin != null ? vpin : 0.0,
            depthImbalance != null ? depthImbalance : 0.0,
            effectiveSpread != null ? effectiveSpread : 0.0,
            isComplete != null && isComplete ? "COMPLETE" : "PARTIAL");
    }
}
