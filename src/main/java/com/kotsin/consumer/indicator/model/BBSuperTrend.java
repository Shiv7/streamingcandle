package com.kotsin.consumer.indicator.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * BBSuperTrend - Bollinger Bands based SuperTrend indicator.
 *
 * Combines Bollinger Bands volatility with SuperTrend logic:
 * - Uses BB bands as dynamic stop levels
 * - Provides trend direction with volatility context
 * - Better adaptation to market conditions
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BBSuperTrend {

    private String symbol;
    private Instant timestamp;
    private String timeframe;

    // ==================== BOLLINGER BANDS ====================
    private double bbMiddle;         // BB Middle (SMA)
    private double bbUpper;          // Upper band (SMA + 2*StdDev)
    private double bbLower;          // Lower band (SMA - 2*StdDev)
    private double bbWidth;          // (Upper - Lower) / Middle * 100
    private double percentB;         // (Close - Lower) / (Upper - Lower)

    // ==================== SUPERTREND ====================
    private double superTrend;       // Current SuperTrend value
    private double atr;              // ATR used for SuperTrend
    private double multiplier;       // ATR multiplier used
    private TrendDirection trend;

    // ==================== BB SUPERTREND COMBO ====================
    private double bbSuperTrendValue;  // Combined indicator value
    private BBSTDirection bbstDirection;
    private double upperBand;        // Dynamic resistance
    private double lowerBand;        // Dynamic support

    // ==================== SIGNAL STATE ====================
    private boolean trendChanged;    // Trend just flipped
    private int barsInTrend;         // How long current trend
    private double trendStrength;    // Strength of current trend (0-100)

    // ==================== VOLATILITY ====================
    private VolatilityState volatilityState;
    private boolean isSqueezing;     // BB narrowing
    private boolean isExpanding;     // BB widening
    private double avgBbWidth;       // Average BB width for comparison

    // ==================== PRICE POSITION ====================
    private PricePosition pricePosition;
    private double distanceFromBand; // Distance to nearest band
    private boolean touchedUpper;    // Price touched upper band
    private boolean touchedLower;    // Price touched lower band

    // ==================== ENUMS ====================

    public enum TrendDirection {
        UP,
        DOWN
    }

    public enum BBSTDirection {
        BULLISH,     // Price above BBST, trend up
        BEARISH,     // Price below BBST, trend down
        NEUTRAL      // In consolidation
    }

    public enum VolatilityState {
        HIGH,        // BB width > 1.5x average
        NORMAL,      // BB width near average
        LOW,         // BB width < 0.5x average (squeeze)
        EXPANDING,   // Width increasing
        CONTRACTING  // Width decreasing
    }

    public enum PricePosition {
        ABOVE_UPPER,   // Above upper band (overbought)
        UPPER_HALF,    // Between middle and upper
        AT_MIDDLE,     // Near middle band
        LOWER_HALF,    // Between middle and lower
        BELOW_LOWER    // Below lower band (oversold)
    }

    // ==================== HELPER METHODS ====================

    public boolean isBullish() {
        return bbstDirection == BBSTDirection.BULLISH;
    }

    public boolean isBearish() {
        return bbstDirection == BBSTDirection.BEARISH;
    }

    public boolean isInUptrend() {
        return trend == TrendDirection.UP;
    }

    public boolean isInDowntrend() {
        return trend == TrendDirection.DOWN;
    }

    public boolean isTrendStrong() {
        return trendStrength > 70 && barsInTrend > 5;
    }

    public boolean isOverbought() {
        return pricePosition == PricePosition.ABOVE_UPPER;
    }

    public boolean isOversold() {
        return pricePosition == PricePosition.BELOW_LOWER;
    }

    public boolean isInSqueeze() {
        return volatilityState == VolatilityState.LOW || isSqueezing;
    }

    public boolean isBreakoutLikely() {
        return isInSqueeze() && (touchedUpper || touchedLower);
    }

    public double getBandPercentage(double price) {
        if (bbUpper == bbLower) return 50;
        return (price - bbLower) / (bbUpper - bbLower) * 100;
    }

    public boolean isPriceNearBand(double price, double tolerancePercent) {
        double upperDist = Math.abs(price - bbUpper) / price * 100;
        double lowerDist = Math.abs(price - bbLower) / price * 100;
        return upperDist <= tolerancePercent || lowerDist <= tolerancePercent;
    }

    /**
     * Get trading bias based on BBST state.
     */
    public String getTradingBias() {
        if (isBullish() && isTrendStrong() && !isOverbought()) {
            return "STRONG_BUY";
        } else if (isBearish() && isTrendStrong() && !isOversold()) {
            return "STRONG_SELL";
        } else if (isBullish()) {
            return "BUY";
        } else if (isBearish()) {
            return "SELL";
        }
        return "NEUTRAL";
    }
}
