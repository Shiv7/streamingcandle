package com.kotsin.consumer.indicator.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * TechnicalIndicators - All technical indicator values for a candle.
 *
 * Contains:
 * - Moving Averages (SMA, EMA)
 * - Momentum (RSI, MACD)
 * - Volatility (Bollinger Bands, ATR)
 * - Trend (SuperTrend, ADX)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TechnicalIndicators {

    private String symbol;
    private Instant timestamp;

    // ==================== MOVING AVERAGES ====================
    private double ema9;
    private double ema21;
    private double ema50;
    private double ema200;
    private double sma20;
    private double sma50;
    private double sma200;
    private double vwma20;  // Volume Weighted MA

    // ==================== RSI ====================
    private double rsi14;
    private double rsiSmoothed;
    private RsiZone rsiZone;

    // ==================== MACD ====================
    private double macdLine;      // EMA12 - EMA26
    private double macdSignal;    // EMA9 of MACD
    private double macdHistogram; // MACD - Signal
    private MacdSignal macdCrossover;

    // ==================== BOLLINGER BANDS ====================
    private double bbUpper;       // SMA20 + 2*StdDev
    private double bbMiddle;      // SMA20
    private double bbLower;       // SMA20 - 2*StdDev
    private double bbWidth;       // (Upper - Lower) / Middle
    private double bbPercentB;    // (Close - Lower) / (Upper - Lower)
    private BollingerPosition bbPosition;

    // ==================== ATR (Average True Range) ====================
    private double atr14;
    private double atrPercent;    // ATR as % of price
    private double atrMultiple;   // Current range / ATR

    // ==================== SUPERTREND ====================
    private double superTrend;
    private SuperTrendDirection superTrendDirection;
    private boolean superTrendFlip;  // Direction changed this candle

    // ==================== ADX (Average Directional Index) ====================
    private double adx14;
    private double plusDI;
    private double minusDI;
    private TrendStrength trendStrength;

    // ==================== STOCHASTIC ====================
    private double stochK;
    private double stochD;
    private StochZone stochZone;

    // ==================== PIVOT POINTS ====================
    private double pivotPoint;
    private double r1, r2, r3;  // Resistance levels
    private double s1, s2, s3;  // Support levels

    // ==================== VOLUME ====================
    private double volume;        // Current candle volume
    private double avgVolume20;   // 20-period average volume
    private double volumeRatio;   // Current volume / average volume

    // ==================== DERIVED SIGNALS ====================
    private boolean goldenCross;     // EMA50 crosses above EMA200
    private boolean deathCross;      // EMA50 crosses below EMA200
    private boolean priceAboveEma21;
    private boolean priceAboveEma50;
    private boolean priceAboveSuperTrend;
    private boolean macdBullish;
    private boolean rsiBullish;

    // ==================== ENUMS ====================

    public enum RsiZone {
        OVERSOLD,      // < 30
        NEUTRAL,       // 30-70
        OVERBOUGHT     // > 70
    }

    public enum MacdSignal {
        BULLISH_CROSS,   // MACD crosses above signal
        BEARISH_CROSS,   // MACD crosses below signal
        BULLISH,         // MACD above signal
        BEARISH,         // MACD below signal
        NEUTRAL
    }

    public enum BollingerPosition {
        ABOVE_UPPER,     // Price above upper band
        UPPER_HALF,      // Price in upper half
        MIDDLE,          // Price near middle
        LOWER_HALF,      // Price in lower half
        BELOW_LOWER      // Price below lower band
    }

    public enum SuperTrendDirection {
        UP,
        DOWN
    }

    public enum TrendStrength {
        STRONG,    // ADX > 25
        MODERATE,  // ADX 20-25
        WEAK,      // ADX 15-20
        NO_TREND   // ADX < 15
    }

    public enum StochZone {
        OVERSOLD,    // < 20
        NEUTRAL,     // 20-80
        OVERBOUGHT   // > 80
    }

    // ==================== CONVENIENCE GETTERS ====================

    /**
     * Convenience getter for ADX (returns adx14).
     */
    public double getAdx() {
        return adx14;
    }

    /**
     * Convenience getter for RSI (returns rsi14).
     */
    public double getRsi() {
        return rsi14;
    }

    /**
     * Check if price is above EMA20 (uses EMA21 as proxy).
     */
    public boolean isAboveEma20() {
        return priceAboveEma21;
    }

    /**
     * Check if price is above EMA50.
     */
    public boolean isAboveEma50() {
        return priceAboveEma50;
    }

    // ==================== HELPER METHODS ====================

    public boolean isBullish() {
        int bullishCount = 0;
        if (priceAboveEma21) bullishCount++;
        if (priceAboveSuperTrend) bullishCount++;
        if (macdBullish) bullishCount++;
        if (rsiBullish) bullishCount++;
        if (rsiZone != RsiZone.OVERBOUGHT) bullishCount++;
        return bullishCount >= 3;
    }

    public boolean isBearish() {
        int bearishCount = 0;
        if (!priceAboveEma21) bearishCount++;
        if (!priceAboveSuperTrend) bearishCount++;
        if (!macdBullish) bearishCount++;
        if (!rsiBullish) bearishCount++;
        if (rsiZone != RsiZone.OVERSOLD) bearishCount++;
        return bearishCount >= 3;
    }

    public boolean isHighVolatility() {
        return atrPercent > 2.0 || bbWidth > 0.04;
    }

    public boolean isLowVolatility() {
        return atrPercent < 0.5 || bbWidth < 0.02;
    }

    public boolean isTrending() {
        return trendStrength == TrendStrength.STRONG ||
               trendStrength == TrendStrength.MODERATE;
    }
}
