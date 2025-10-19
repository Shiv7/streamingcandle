package com.kotsin.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Candle data for a specific timeframe
 * Contains OHLCV + completion status
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandleData {
    
    private Double open;
    private Double high;
    private Double low;
    private Double close;
    private Long volume;
    private Long windowStart;
    private Long windowEnd;
    private Boolean isComplete;
    private String exchange;
    private String exchangeType;
    
    /**
     * Calculate price change
     */
    public Double getPriceChange() {
        if (open == null || close == null) return null;
        return close - open;
    }
    
    /**
     * Calculate price change percentage
     */
    public Double getPriceChangePercent() {
        if (open == null || close == null || open == 0) return null;
        return ((close - open) / open) * 100;
    }
    
    /**
     * Calculate true range
     */
    public Double getTrueRange() {
        if (high == null || low == null) return null;
        return high - low;
    }
    
    /**
     * Calculate body size
     */
    public Double getBodySize() {
        if (open == null || close == null) return null;
        return Math.abs(close - open);
    }
    
    /**
     * Calculate upper shadow
     */
    public Double getUpperShadow() {
        if (high == null || open == null || close == null) return null;
        return high - Math.max(open, close);
    }
    
    /**
     * Calculate lower shadow
     */
    public Double getLowerShadow() {
        if (low == null || open == null || close == null) return null;
        return Math.min(open, close) - low;
    }
    
    /**
     * Check if candle is bullish
     */
    public Boolean isBullish() {
        if (open == null || close == null) return null;
        return close > open;
    }
    
    /**
     * Check if candle is bearish
     */
    public Boolean isBearish() {
        if (open == null || close == null) return null;
        return close < open;
    }
    
    /**
     * Check if candle is doji (small body)
     */
    public Boolean isDoji() {
        if (open == null || close == null) return null;
        return Math.abs(close - open) < (getTrueRange() * 0.1); // Body < 10% of range
    }
    
    /**
     * Get window duration in minutes
     */
    public Long getWindowDurationMinutes() {
        if (windowStart == null || windowEnd == null) return null;
        return (windowEnd - windowStart) / (60 * 1000);
    }
    
    /**
     * Get display string for logging
     */
    public String getDisplayString() {
        return String.format("OHLCV[%.2f,%.2f,%.2f,%.2f,%d] %s", 
            open, high, low, close, volume, 
            isComplete ? "COMPLETE" : "PARTIAL");
    }
}
