package com.kotsin.consumer.enrichment.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.kotsin.consumer.enrichment.enricher.MTFSuperTrendAggregator;
import java.util.List;
import java.util.Map;

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

    // ======================== CAMARILLA PIVOTS ========================

    /**
     * Camarilla H4 (Strong resistance - potential breakout level)
     */
    private Double camH4;

    /**
     * Camarilla H3 (Resistance - sell zone for shorts)
     */
    private Double camH3;

    /**
     * Camarilla H2 (Minor resistance)
     */
    private Double camH2;

    /**
     * Camarilla H1 (Weak resistance)
     */
    private Double camH1;

    /**
     * Camarilla L1 (Weak support)
     */
    private Double camL1;

    /**
     * Camarilla L2 (Minor support)
     */
    private Double camL2;

    /**
     * Camarilla L3 (Support - buy zone for longs)
     */
    private Double camL3;

    /**
     * Camarilla L4 (Strong support - potential breakdown level)
     */
    private Double camL4;

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

    // ======================== MTF SUPERTREND ========================

    /**
     * Aggregated SuperTrend direction across all timeframes
     * BULLISH = 70%+ timeframes bullish
     * BEARISH = 70%+ timeframes bearish
     * CONFLICTING = Mixed signals (LTF vs HTF disagreement)
     * NEUTRAL = No data
     */
    private MTFSuperTrendAggregator.AggregatedDirection mtfAggregatedDirection;

    /**
     * Percentage of timeframes that are bullish (0.0 - 1.0)
     */
    private double mtfBullishPercentage;

    /**
     * Are higher timeframes (15m+) bullish?
     * This is the key signal for swing/position trades.
     */
    private boolean mtfHtfBullish;

    /**
     * Is there a conflict between lower and higher timeframes?
     * True = LTF and HTF disagree (potential reversal)
     */
    private boolean mtfHasConflict;

    /**
     * Individual timeframe directions (timeframe -> bullish)
     * e.g., {"1m": true, "5m": true, "15m": false, "30m": false}
     */
    private Map<String, Boolean> mtfTimeframeDirections;

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
     * Note: Using Stream.of() instead of List.of() to handle null values
     */
    public List<Double> getSupportLevels() {
        return java.util.stream.Stream.of(dailyS1, dailyS2, dailyS3, weeklyS1, monthlyS1)
                .filter(l -> l != null && l > 0)
                .sorted()
                .toList();
    }

    /**
     * Get all resistance levels
     * Note: Using Stream.of() instead of List.of() to handle null values
     */
    public List<Double> getResistanceLevels() {
        return java.util.stream.Stream.of(dailyR1, dailyR2, dailyR3, weeklyR1, monthlyR1)
                .filter(l -> l != null && l > 0)
                .sorted()
                .toList();
    }

    // ======================== MTF HELPER METHODS ========================

    /**
     * Check if MTF analysis is bullish overall
     * Uses aggregated direction from all timeframes
     */
    public boolean isMtfBullish() {
        return mtfAggregatedDirection == MTFSuperTrendAggregator.AggregatedDirection.BULLISH;
    }

    /**
     * Check if MTF analysis is bearish overall
     * Uses aggregated direction from all timeframes
     */
    public boolean isMtfBearish() {
        return mtfAggregatedDirection == MTFSuperTrendAggregator.AggregatedDirection.BEARISH;
    }

    /**
     * Check if higher timeframes (15m+) support a long trade
     * This should be used for SWING_LONG validation instead of single TF SuperTrend
     */
    public boolean isMtfSupportingLong() {
        return mtfHtfBullish || mtfBullishPercentage >= 0.6;
    }

    /**
     * Check if higher timeframes (15m+) support a short trade
     * This should be used for SWING_SHORT validation instead of single TF SuperTrend
     */
    public boolean isMtfSupportingShort() {
        return !mtfHtfBullish || mtfBullishPercentage <= 0.4;
    }

    /**
     * Get MTF signal modifier
     * Boosts signals aligned with MTF direction, reduces counter-trend signals
     */
    public double getMtfSignalModifier(boolean isLongSignal) {
        if (mtfAggregatedDirection == null ||
            mtfAggregatedDirection == MTFSuperTrendAggregator.AggregatedDirection.NEUTRAL) {
            return 1.0;
        }

        boolean mtfBullish = mtfAggregatedDirection == MTFSuperTrendAggregator.AggregatedDirection.BULLISH;
        boolean aligned = (isLongSignal && mtfBullish) || (!isLongSignal && !mtfBullish);
        boolean conflicting = mtfAggregatedDirection == MTFSuperTrendAggregator.AggregatedDirection.CONFLICTING;

        if (conflicting) {
            return 0.8; // Reduce confidence when timeframes disagree
        }

        if (aligned) {
            // Boost based on how strong the alignment is
            double boost = 1.0 + (mtfBullishPercentage - 0.5) * 0.4; // Max +20%
            return Math.min(1.2, Math.max(1.0, boost));
        } else {
            // Reduce when counter to MTF direction
            return 0.7;
        }
    }

    /**
     * Get MTF summary string
     */
    public String getMtfSummary() {
        if (mtfAggregatedDirection == null) return "MTF: N/A";

        StringBuilder sb = new StringBuilder("MTF: ");
        sb.append(mtfAggregatedDirection.name());
        sb.append(String.format(" (%.0f%% bull", mtfBullishPercentage * 100));
        sb.append(", HTF:").append(mtfHtfBullish ? "BULL" : "BEAR");
        if (mtfHasConflict) sb.append(", CONFLICT");
        sb.append(")");
        return sb.toString();
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
                .mtfAggregatedDirection(MTFSuperTrendAggregator.AggregatedDirection.NEUTRAL)
                .mtfBullishPercentage(0.5)
                .mtfHtfBullish(true)
                .mtfHasConflict(false)
                .build();
    }
}
