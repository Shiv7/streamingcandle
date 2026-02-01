package com.kotsin.consumer.indicator.calculator;

import com.kotsin.consumer.indicator.model.BBSuperTrend;
import com.kotsin.consumer.indicator.model.BBSuperTrend.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BBSuperTrendCalculator - Calculates BB-SuperTrend combination indicator.
 *
 * Combines:
 * - Bollinger Bands for volatility and mean reversion
 * - SuperTrend for trend direction
 * - Dynamic support/resistance based on both
 */
@Component
@Slf4j
public class BBSuperTrendCalculator {

    // Default parameters
    private static final int BB_PERIOD = 20;
    private static final double BB_MULTIPLIER = 2.0;
    private static final int ATR_PERIOD = 14;
    private static final double ST_MULTIPLIER = 3.0;

    // State tracking
    private final Map<String, IndicatorState> stateMap = new ConcurrentHashMap<>();

    /**
     * Calculate BBSuperTrend for given OHLC data.
     *
     * @param symbol    Symbol identifier
     * @param timeframe Timeframe
     * @param closes    Close prices (oldest to newest)
     * @param highs     High prices
     * @param lows      Low prices
     * @return BBSuperTrend indicator values
     */
    public BBSuperTrend calculate(String symbol, String timeframe,
                                   double[] closes, double[] highs, double[] lows) {

        if (closes == null || closes.length < BB_PERIOD + 1) {
            return defaultBBST(symbol, timeframe);
        }

        String key = symbol + "_" + timeframe;
        IndicatorState state = stateMap.computeIfAbsent(key, k -> new IndicatorState());

        int n = closes.length;
        double close = closes[n - 1];
        double high = highs[n - 1];
        double low = lows[n - 1];

        // Calculate Bollinger Bands
        double sma = calculateSMA(closes, BB_PERIOD);
        double stdDev = calculateStdDev(closes, BB_PERIOD, sma);
        double bbUpper = sma + BB_MULTIPLIER * stdDev;
        double bbLower = sma - BB_MULTIPLIER * stdDev;
        double bbWidth = sma > 0 ? (bbUpper - bbLower) / sma * 100 : 0;
        double percentB = (bbUpper - bbLower) > 0 ? (close - bbLower) / (bbUpper - bbLower) : 0.5;

        // Calculate ATR
        double atr = calculateATR(highs, lows, closes, ATR_PERIOD);

        // Calculate SuperTrend
        double hl2 = (high + low) / 2;
        double basicUpperBand = hl2 + ST_MULTIPLIER * atr;
        double basicLowerBand = hl2 - ST_MULTIPLIER * atr;

        // Final bands with state tracking
        double finalUpperBand = basicUpperBand;
        double finalLowerBand = basicLowerBand;

        if (state.prevFinalUpperBand > 0) {
            if (closes[n - 2] <= state.prevFinalUpperBand) {
                finalUpperBand = Math.min(basicUpperBand, state.prevFinalUpperBand);
            }
        }

        if (state.prevFinalLowerBand > 0) {
            if (closes[n - 2] >= state.prevFinalLowerBand) {
                finalLowerBand = Math.max(basicLowerBand, state.prevFinalLowerBand);
            }
        }

        // Determine SuperTrend direction
        TrendDirection trend;
        double superTrend;

        if (state.prevSuperTrend == 0) {
            trend = close > sma ? TrendDirection.UP : TrendDirection.DOWN;
            superTrend = trend == TrendDirection.UP ? finalLowerBand : finalUpperBand;
        } else {
            if (state.prevTrend == TrendDirection.UP) {
                if (close < state.prevFinalLowerBand) {
                    trend = TrendDirection.DOWN;
                    superTrend = finalUpperBand;
                } else {
                    trend = TrendDirection.UP;
                    superTrend = finalLowerBand;
                }
            } else {
                if (close > state.prevFinalUpperBand) {
                    trend = TrendDirection.UP;
                    superTrend = finalLowerBand;
                } else {
                    trend = TrendDirection.DOWN;
                    superTrend = finalUpperBand;
                }
            }
        }

        // Detect trend change
        boolean trendChanged = state.prevTrend != null && state.prevTrend != trend;
        int barsInTrend = trendChanged ? 1 : state.barsInTrend + 1;

        // BB SuperTrend combination
        double bbstValue;
        BBSTDirection bbstDirection;

        if (trend == TrendDirection.UP && close > sma) {
            bbstDirection = BBSTDirection.BULLISH;
            bbstValue = Math.max(superTrend, bbLower);
        } else if (trend == TrendDirection.DOWN && close < sma) {
            bbstDirection = BBSTDirection.BEARISH;
            bbstValue = Math.min(superTrend, bbUpper);
        } else {
            bbstDirection = BBSTDirection.NEUTRAL;
            bbstValue = sma;
        }

        // Volatility analysis
        state.bbWidthHistory.add(bbWidth);
        if (state.bbWidthHistory.size() > 20) {
            state.bbWidthHistory.remove(0);
        }

        double avgBbWidth = state.bbWidthHistory.stream().mapToDouble(d -> d).average().orElse(bbWidth);
        VolatilityState volatilityState = determineVolatilityState(bbWidth, avgBbWidth,
            state.prevBbWidth > 0 ? state.prevBbWidth : bbWidth);

        boolean isSqueezing = bbWidth < avgBbWidth * 0.7;
        boolean isExpanding = bbWidth > avgBbWidth * 1.3;

        // Price position
        PricePosition pricePosition = determinePricePosition(close, bbUpper, bbLower, sma);

        // Trend strength (based on distance from SuperTrend and BB position)
        double trendStrength = calculateTrendStrength(close, superTrend, bbUpper, bbLower, percentB, trend);

        // Check band touches
        boolean touchedUpper = high >= bbUpper;
        boolean touchedLower = low <= bbLower;

        // Update state
        state.prevFinalUpperBand = finalUpperBand;
        state.prevFinalLowerBand = finalLowerBand;
        state.prevSuperTrend = superTrend;
        state.prevTrend = trend;
        state.prevBbWidth = bbWidth;
        state.barsInTrend = barsInTrend;

        return BBSuperTrend.builder()
            .symbol(symbol)
            .timeframe(timeframe)
            .timestamp(Instant.now())
            // Bollinger Bands
            .bbMiddle(sma)
            .bbUpper(bbUpper)
            .bbLower(bbLower)
            .bbWidth(bbWidth)
            .percentB(percentB)
            // SuperTrend
            .superTrend(superTrend)
            .atr(atr)
            .multiplier(ST_MULTIPLIER)
            .trend(trend)
            // BB SuperTrend Combo
            .bbSuperTrendValue(bbstValue)
            .bbstDirection(bbstDirection)
            .upperBand(finalUpperBand)
            .lowerBand(finalLowerBand)
            // Signal State
            .trendChanged(trendChanged)
            .barsInTrend(barsInTrend)
            .trendStrength(trendStrength)
            // Volatility
            .volatilityState(volatilityState)
            .isSqueezing(isSqueezing)
            .isExpanding(isExpanding)
            .avgBbWidth(avgBbWidth)
            // Price Position
            .pricePosition(pricePosition)
            .distanceFromBand(Math.min(bbUpper - close, close - bbLower))
            .touchedUpper(touchedUpper)
            .touchedLower(touchedLower)
            .build();
    }

    private double calculateSMA(double[] data, int period) {
        int n = data.length;
        int start = Math.max(0, n - period);
        double sum = 0;
        for (int i = start; i < n; i++) {
            sum += data[i];
        }
        return sum / (n - start);
    }

    private double calculateStdDev(double[] data, int period, double mean) {
        int n = data.length;
        int start = Math.max(0, n - period);
        double sumSq = 0;
        for (int i = start; i < n; i++) {
            sumSq += Math.pow(data[i] - mean, 2);
        }
        return Math.sqrt(sumSq / (n - start));
    }

    private double calculateATR(double[] highs, double[] lows, double[] closes, int period) {
        int n = highs.length;
        if (n < 2) return 0;

        double[] tr = new double[n];
        tr[0] = highs[0] - lows[0];

        for (int i = 1; i < n; i++) {
            double hl = highs[i] - lows[i];
            double hc = Math.abs(highs[i] - closes[i - 1]);
            double lc = Math.abs(lows[i] - closes[i - 1]);
            tr[i] = Math.max(hl, Math.max(hc, lc));
        }

        return calculateSMA(tr, period);
    }

    private VolatilityState determineVolatilityState(double bbWidth, double avgWidth, double prevWidth) {
        if (bbWidth > avgWidth * 1.5) {
            return VolatilityState.HIGH;
        } else if (bbWidth < avgWidth * 0.5) {
            return VolatilityState.LOW;
        } else if (bbWidth > prevWidth * 1.1) {
            return VolatilityState.EXPANDING;
        } else if (bbWidth < prevWidth * 0.9) {
            return VolatilityState.CONTRACTING;
        }
        return VolatilityState.NORMAL;
    }

    private PricePosition determinePricePosition(double close, double upper, double lower, double middle) {
        if (close > upper) return PricePosition.ABOVE_UPPER;
        if (close < lower) return PricePosition.BELOW_LOWER;

        double midToUpper = (upper + middle) / 2;
        double midToLower = (middle + lower) / 2;

        if (close > midToUpper) return PricePosition.UPPER_HALF;
        if (close < midToLower) return PricePosition.LOWER_HALF;
        return PricePosition.AT_MIDDLE;
    }

    private double calculateTrendStrength(double close, double superTrend, double upper, double lower,
                                           double percentB, TrendDirection trend) {
        double strength = 0;

        // Distance from SuperTrend (max 40 points)
        double stDistance = Math.abs(close - superTrend) / close * 100;
        strength += Math.min(40, stDistance * 10);

        // PercentB position (max 30 points)
        if (trend == TrendDirection.UP) {
            strength += percentB * 30;
        } else {
            strength += (1 - percentB) * 30;
        }

        // Price vs bands (max 30 points)
        double range = upper - lower;
        if (range > 0) {
            if (trend == TrendDirection.UP) {
                strength += ((close - lower) / range) * 30;
            } else {
                strength += ((upper - close) / range) * 30;
            }
        }

        return Math.min(100, strength);
    }

    private BBSuperTrend defaultBBST(String symbol, String timeframe) {
        return BBSuperTrend.builder()
            .symbol(symbol)
            .timeframe(timeframe)
            .timestamp(Instant.now())
            .trend(TrendDirection.UP)
            .bbstDirection(BBSTDirection.NEUTRAL)
            .volatilityState(VolatilityState.NORMAL)
            .pricePosition(PricePosition.AT_MIDDLE)
            .build();
    }

    // State tracking class
    private static class IndicatorState {
        double prevFinalUpperBand = 0;
        double prevFinalLowerBand = 0;
        double prevSuperTrend = 0;
        TrendDirection prevTrend = null;
        double prevBbWidth = 0;
        int barsInTrend = 0;
        List<Double> bbWidthHistory = new ArrayList<>();
    }
}
