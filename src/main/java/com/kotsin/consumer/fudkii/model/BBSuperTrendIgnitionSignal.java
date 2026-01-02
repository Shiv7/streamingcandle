package com.kotsin.consumer.fudkii.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * BBSuperTrendIgnitionSignal - Enhanced signal for BB + SuperTrend strict simultaneity
 * 
 * Emitted when both BB breakout AND SuperTrend flip occur on the SAME 30m candle.
 * 
 * Topic: kotsin_FUDKII
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BBSuperTrendIgnitionSignal {

    // ========== Basic Identification ==========
    private String scripCode;
    private String companyName;
    private long timestamp;
    private String timeframe;
    
    // ========== OHLCV Data ==========
    private double open;
    private double high;
    private double low;
    private double close;
    private long volume;
    
    // ========== Bollinger Band Metrics ==========
    private double bbUpper;
    private double bbMiddle;  // 20 SMA
    private double bbLower;
    private double bbWidth;  // (upper - lower) / middle
    private double bbPercentB;  // (close - lower) / (upper - lower)
    private boolean bbBreakoutUp;   // close > upper
    private boolean bbBreakoutDown; // close < lower
    private boolean bbBreakoutOnCurrentCandle;  // Validation flag
    
    // ========== SuperTrend Metrics ==========
    private double superTrend;
    private double atr;
    private boolean superTrendBullish;  // price > superTrend
    private boolean superTrendFlipped;  // direction just changed
    private boolean stFlipOnCurrentCandle;  // Validation flag
    
    // ========== Volume Metrics ==========
    private double volumeZScore;  // vs 20-period average
    
    // ========== Signal Information ==========
    private String direction;  // "BULLISH" or "BEARISH"
    private double signalStrength;  // 0-1 score based on BB distance, ST distance, volume
    private boolean simultaneityValid;  // Both BB breakout AND ST flip on same candle
    
    /**
     * Check if this is a valid ignition signal
     */
    public boolean isValid() {
        return simultaneityValid && (bbBreakoutOnCurrentCandle && stFlipOnCurrentCandle);
    }
    
    /**
     * Check if signal is bullish
     */
    public boolean isBullish() {
        return "BULLISH".equals(direction);
    }
    
    /**
     * Check if signal is bearish
     */
    public boolean isBearish() {
        return "BEARISH".equals(direction);
    }
}

