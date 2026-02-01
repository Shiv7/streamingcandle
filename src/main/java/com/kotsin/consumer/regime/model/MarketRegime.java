package com.kotsin.consumer.regime.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * MarketRegime - Market regime classification.
 *
 * Identifies:
 * - Trend vs Range
 * - Volatility state
 * - Momentum characteristics
 * - Optimal trading strategies
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketRegime {

    private String symbol;
    private Instant timestamp;
    private String timeframe;

    // ==================== REGIME CLASSIFICATION ====================
    private RegimeType regimeType;
    private TrendStrength trendStrength;
    private VolatilityState volatilityState;
    private MomentumState momentumState;

    // ==================== TREND METRICS ====================
    private double adxValue;             // ADX for trend strength
    private double plusDI;               // +DI
    private double minusDI;              // -DI
    private double trendDirection;       // 1 = up, -1 = down, 0 = neutral
    private int trendDuration;           // Bars in current trend

    // ==================== VOLATILITY METRICS ====================
    private double atr;
    private double atrPercent;           // ATR as % of price
    private double historicalVolatility;
    private double impliedVolatility;
    private double volatilityPercentile; // Rank 0-100
    private double bbWidth;              // Bollinger Band width

    // ==================== MOMENTUM METRICS ====================
    private double rsi;
    private double macdHistogram;
    private double momentumScore;        // Composite momentum (-100 to 100)

    // ==================== REGIME QUALITY ====================
    private double regimeConfidence;     // How certain is the classification (0-100)
    private int regimeAge;               // How long in this regime
    private boolean isTransitioning;     // Regime may be changing
    private RegimeType likelyNextRegime; // If transitioning

    // ==================== TRADING IMPLICATIONS ====================
    private TradingMode recommendedMode;
    private double expectedRange;        // Expected price range
    private String[] suitableStrategies;
    private String[] avoidStrategies;

    // ==================== ENUMS ====================

    public enum RegimeType {
        STRONG_UPTREND,     // Clear bullish trend with momentum
        WEAK_UPTREND,       // Uptrend losing steam
        STRONG_DOWNTREND,   // Clear bearish trend with momentum
        WEAK_DOWNTREND,     // Downtrend losing steam
        RANGING,            // Sideways consolidation
        CHOPPY,             // Erratic, no clear direction
        BREAKOUT,           // Breaking out of range
        BREAKDOWN           // Breaking down from range
    }

    public enum TrendStrength {
        VERY_STRONG,    // ADX > 40
        STRONG,         // ADX 25-40
        MODERATE,       // ADX 20-25
        WEAK,           // ADX 15-20
        ABSENT          // ADX < 15
    }

    public enum VolatilityState {
        VERY_HIGH,      // > 2x average
        HIGH,           // 1.5x - 2x average
        NORMAL,         // 0.7x - 1.5x average
        LOW,            // 0.5x - 0.7x average
        VERY_LOW        // < 0.5x average (squeeze)
    }

    public enum MomentumState {
        STRONG_BULLISH,
        BULLISH,
        NEUTRAL,
        BEARISH,
        STRONG_BEARISH
    }

    public enum TradingMode {
        TREND_FOLLOWING,    // Trade with the trend
        MEAN_REVERSION,     // Fade extremes
        BREAKOUT,           // Trade breakouts
        AVOID               // Stay out of market
    }

    // ==================== HELPER METHODS ====================

    public boolean isTrending() {
        return regimeType == RegimeType.STRONG_UPTREND ||
               regimeType == RegimeType.WEAK_UPTREND ||
               regimeType == RegimeType.STRONG_DOWNTREND ||
               regimeType == RegimeType.WEAK_DOWNTREND;
    }

    public boolean isRanging() {
        return regimeType == RegimeType.RANGING;
    }

    public boolean isChoppy() {
        return regimeType == RegimeType.CHOPPY;
    }

    public boolean isBullish() {
        return regimeType == RegimeType.STRONG_UPTREND ||
               regimeType == RegimeType.WEAK_UPTREND ||
               regimeType == RegimeType.BREAKOUT;
    }

    public boolean isBearish() {
        return regimeType == RegimeType.STRONG_DOWNTREND ||
               regimeType == RegimeType.WEAK_DOWNTREND ||
               regimeType == RegimeType.BREAKDOWN;
    }

    public boolean isHighVolatility() {
        return volatilityState == VolatilityState.VERY_HIGH ||
               volatilityState == VolatilityState.HIGH;
    }

    public boolean isLowVolatility() {
        return volatilityState == VolatilityState.VERY_LOW ||
               volatilityState == VolatilityState.LOW;
    }

    public boolean isSqueeze() {
        return volatilityState == VolatilityState.VERY_LOW && bbWidth < 3;
    }

    public boolean isTradeable() {
        return recommendedMode != TradingMode.AVOID && regimeConfidence > 60;
    }

    public boolean isTrendFollowingRegime() {
        return isTrending() && trendStrength != TrendStrength.ABSENT;
    }

    public boolean isMeanReversionRegime() {
        return isRanging() && !isHighVolatility();
    }

    public boolean isBreakoutSetup() {
        return isSqueeze() || (isRanging() && regimeAge > 10);
    }

    public String getRegimeSummary() {
        return String.format("%s (%s volatility, %s momentum, %.0f%% confidence)",
            regimeType, volatilityState, momentumState, regimeConfidence);
    }
}
