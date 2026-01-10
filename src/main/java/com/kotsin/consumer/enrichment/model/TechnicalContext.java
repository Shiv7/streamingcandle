package com.kotsin.consumer.enrichment.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * TechnicalContext - Technical indicator context for trading signals
 *
 * Contains:
 * - SuperTrend indicator (trend following)
 * - Bollinger Bands (volatility and mean reversion)
 * - Pivot levels (support/resistance)
 * - ATR (volatility for stop placement)
 * - Session VWAP (intraday fair value)
 *
 * These indicators complement microstructure analysis:
 * - Microstructure tells us WHO is trading
 * - Technical tells us WHERE key levels are
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TechnicalContext {

    // ======================== IDENTITY ========================

    private String familyId;
    private String timeframe;
    private double currentPrice;

    // ======================== SUPERTREND ========================

    /**
     * Current SuperTrend value
     */
    private double superTrendValue;

    /**
     * SuperTrend direction (true = bullish, false = bearish)
     */
    private boolean superTrendBullish;

    /**
     * Did SuperTrend just flip?
     */
    private boolean superTrendFlip;

    /**
     * Candles since last SuperTrend flip
     */
    private int candlesSinceStFlip;

    /**
     * SuperTrend ATR multiplier used
     */
    private double superTrendMultiplier;

    /**
     * SuperTrend ATR period used
     */
    private int superTrendPeriod;

    // ======================== BOLLINGER BANDS ========================

    /**
     * Upper Bollinger Band
     */
    private double bbUpper;

    /**
     * Middle Bollinger Band (SMA)
     */
    private double bbMiddle;

    /**
     * Lower Bollinger Band
     */
    private double bbLower;

    /**
     * Bollinger Band width (upper - lower)
     */
    private double bbWidth;

    /**
     * Bollinger Band width as % of middle
     */
    private double bbWidthPct;

    /**
     * %B indicator: (price - lower) / (upper - lower)
     * 0 = at lower band, 1 = at upper band
     */
    private double bbPercentB;

    /**
     * Is BB squeezing (low volatility)?
     */
    private boolean bbSqueezing;

    /**
     * BB squeeze threshold used
     */
    private double bbSqueezeThreshold;

    // ======================== PIVOT LEVELS ========================

    /**
     * Daily pivot point
     */
    private Double dailyPivot;

    /**
     * Daily resistance levels R1, R2, R3
     */
    private Double dailyR1;
    private Double dailyR2;
    private Double dailyR3;

    /**
     * Daily support levels S1, S2, S3
     */
    private Double dailyS1;
    private Double dailyS2;
    private Double dailyS3;

    /**
     * Weekly pivot point
     */
    private Double weeklyPivot;

    /**
     * Weekly R1, S1
     */
    private Double weeklyR1;
    private Double weeklyS1;

    /**
     * Monthly pivot point
     */
    private Double monthlyPivot;

    /**
     * Monthly R1, S1
     */
    private Double monthlyR1;
    private Double monthlyS1;

    /**
     * Nearest support level (any timeframe)
     */
    private Double nearestSupport;

    /**
     * Nearest resistance level (any timeframe)
     */
    private Double nearestResistance;

    /**
     * Distance to nearest support (%)
     */
    private Double distanceToSupportPct;

    /**
     * Distance to nearest resistance (%)
     */
    private Double distanceToResistancePct;

    // ======================== ATR ========================

    /**
     * Average True Range (14 period)
     */
    private double atr;

    /**
     * ATR as percentage of price
     */
    private double atrPct;

    /**
     * ATR percentile (is current ATR high or low vs history?)
     */
    private double atrPercentile;

    /**
     * Is volatility expanding?
     */
    private boolean volatilityExpanding;

    // ======================== SESSION DATA ========================

    /**
     * Session VWAP
     */
    private Double sessionVwap;

    /**
     * Session high
     */
    private Double sessionHigh;

    /**
     * Session low
     */
    private Double sessionLow;

    /**
     * Session open
     */
    private Double sessionOpen;

    /**
     * Price position relative to VWAP
     * Positive = above VWAP, negative = below
     */
    private Double vwapDeviation;

    // ======================== HELPER METHODS ========================

    /**
     * Check if price is at support
     */
    public boolean isAtSupport() {
        return distanceToSupportPct != null && distanceToSupportPct < 0.3;
    }

    /**
     * Check if price is at resistance
     */
    public boolean isAtResistance() {
        return distanceToResistancePct != null && distanceToResistancePct < 0.3;
    }

    /**
     * Check if price is above VWAP
     */
    public boolean isAboveVwap() {
        return vwapDeviation != null && vwapDeviation > 0;
    }

    /**
     * Check if price is below VWAP
     */
    public boolean isBelowVwap() {
        return vwapDeviation != null && vwapDeviation < 0;
    }

    /**
     * Get SuperTrend stop loss level
     */
    public double getStopLossFromSuperTrend() {
        return superTrendValue;
    }

    /**
     * Get BB-based stop loss (below lower band)
     */
    public double getStopLossFromBB() {
        return bbLower - (bbUpper - bbLower) * 0.1; // 10% below lower band
    }

    /**
     * Get volatility regime
     */
    public String getVolatilityRegime() {
        if (bbSqueezing) return "SQUEEZE";
        if (volatilityExpanding) return "EXPANDING";
        if (atrPercentile > 80) return "HIGH";
        if (atrPercentile < 20) return "LOW";
        return "NORMAL";
    }

    /**
     * Get price position description
     */
    public String getPricePosition() {
        StringBuilder pos = new StringBuilder();

        if (superTrendBullish) {
            pos.append("Above SuperTrend. ");
        } else {
            pos.append("Below SuperTrend. ");
        }

        if (bbPercentB > 0.8) {
            pos.append("At upper BB. ");
        } else if (bbPercentB < 0.2) {
            pos.append("At lower BB. ");
        } else if (bbPercentB > 0.45 && bbPercentB < 0.55) {
            pos.append("At BB middle. ");
        }

        if (isAtSupport()) {
            pos.append("Near support. ");
        }
        if (isAtResistance()) {
            pos.append("Near resistance. ");
        }

        return pos.toString().trim();
    }

    /**
     * Get signal modifier based on technical context
     */
    public double getSignalModifier(boolean isLongSignal) {
        double modifier = 1.0;

        // Boost signals aligned with SuperTrend
        if (isLongSignal && superTrendBullish) modifier *= 1.1;
        if (!isLongSignal && !superTrendBullish) modifier *= 1.1;

        // Reduce signals counter to SuperTrend
        if (isLongSignal && !superTrendBullish) modifier *= 0.8;
        if (!isLongSignal && superTrendBullish) modifier *= 0.8;

        // Boost at support for longs, resistance for shorts
        if (isLongSignal && isAtSupport()) modifier *= 1.15;
        if (!isLongSignal && isAtResistance()) modifier *= 1.15;

        // Reduce at resistance for longs, support for shorts
        if (isLongSignal && isAtResistance()) modifier *= 0.85;
        if (!isLongSignal && isAtSupport()) modifier *= 0.85;

        // BB position
        if (isLongSignal && bbPercentB < 0.2) modifier *= 1.1; // Oversold
        if (!isLongSignal && bbPercentB > 0.8) modifier *= 1.1; // Overbought

        return Math.max(0.5, Math.min(1.5, modifier));
    }

    /**
     * Get all support levels
     */
    public List<Double> getSupportLevels() {
        return List.of(dailyS1, dailyS2, dailyS3, weeklyS1, monthlyS1).stream()
                .filter(l -> l != null && l > 0)
                .sorted()
                .toList();
    }

    /**
     * Get all resistance levels
     */
    public List<Double> getResistanceLevels() {
        return List.of(dailyR1, dailyR2, dailyR3, weeklyR1, monthlyR1).stream()
                .filter(l -> l != null && l > 0)
                .sorted()
                .toList();
    }

    /**
     * Factory method for empty context
     */
    public static TechnicalContext empty() {
        return TechnicalContext.builder()
                .superTrendBullish(true)
                .superTrendFlip(false)
                .bbPercentB(0.5)
                .bbSqueezing(false)
                .volatilityExpanding(false)
                .build();
    }
}
