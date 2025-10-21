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

    // Buy/Sell volume separation (NEW)
    private Long buyVolume;              // Volume from buy-side trades
    private Long sellVolume;             // Volume from sell-side trades
    private Double volumeDelta;          // buyVolume - sellVolume
    private Double volumeDeltaPercent;   // (buyVolume - sellVolume) / totalVolume * 100

    // Volume profile (NEW)
    private Double vwap;                 // Volume-weighted average price
    private Integer tickCount;           // Number of ticks in this candle
    
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
     * Check if there's buying pressure (buy volume > sell volume)
     */
    public Boolean hasBuyingPressure() {
        if (buyVolume == null || sellVolume == null) return null;
        return buyVolume > sellVolume;
    }

    /**
     * Check if there's selling pressure (sell volume > buy volume)
     */
    public Boolean hasSellingPressure() {
        if (buyVolume == null || sellVolume == null) return null;
        return sellVolume > buyVolume;
    }

    /**
     * Check for volume delta divergence (price up but volume delta down)
     */
    public Boolean hasVolumeDeltaDivergence() {
        if (isBullish() == null || volumeDelta == null) return null;
        return isBullish() && volumeDelta < 0;  // Price up but sells dominate
    }

    /**
     * Get buy/sell volume ratio
     */
    public Double getBuySellRatio() {
        if (buyVolume == null || sellVolume == null || sellVolume == 0) return null;
        return (double) buyVolume / sellVolume;
    }

    /**
     * Get display string for logging
     */
    public String getDisplayString() {
        if (buyVolume != null && sellVolume != null) {
            return String.format("OHLCV[%.2f,%.2f,%.2f,%.2f,%d] BuyVol:%d SellVol:%d Delta:%.1f%% %s",
                open, high, low, close, volume,
                buyVolume, sellVolume, volumeDeltaPercent,
                isComplete != null && isComplete ? "COMPLETE" : "PARTIAL");
        }
        return String.format("OHLCV[%.2f,%.2f,%.2f,%.2f,%d] %s",
            open, high, low, close, volume,
            isComplete != null && isComplete ? "COMPLETE" : "PARTIAL");
    }
}
