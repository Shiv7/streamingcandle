package com.kotsin.consumer.regime.detector;

import com.kotsin.consumer.regime.model.MarketRegime;
import com.kotsin.consumer.regime.model.MarketRegime.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RegimeDetector - Detects and tracks market regime.
 *
 * Features:
 * - Trend/Range classification
 * - Volatility state detection
 * - Momentum analysis
 * - Regime transition detection
 * - Trading mode recommendations
 */
@Component
@Slf4j
public class RegimeDetector {

    // Cached regimes per symbol
    private final Map<String, MarketRegime> currentRegimes = new ConcurrentHashMap<>();

    // Historical regime data for transitions
    private final Map<String, List<RegimeType>> regimeHistory = new ConcurrentHashMap<>();

    // Configuration thresholds
    private static final double ADX_STRONG_TREND = 40;
    private static final double ADX_TREND = 25;
    private static final double ADX_MODERATE_TREND = 20;
    private static final double ADX_WEAK = 15;

    private static final double RSI_OVERBOUGHT = 70;
    private static final double RSI_OVERSOLD = 30;
    private static final double RSI_NEUTRAL_UPPER = 60;
    private static final double RSI_NEUTRAL_LOWER = 40;

    private static final int MAX_HISTORY = 50;

    /**
     * Detect market regime from indicators.
     *
     * @param symbol    Symbol identifier
     * @param timeframe Timeframe
     * @param adx       ADX value
     * @param plusDI    +DI value
     * @param minusDI   -DI value
     * @param rsi       RSI value
     * @param macdHist  MACD histogram
     * @param atr       ATR value
     * @param atrPct    ATR as percentage of price
     * @param bbWidth   Bollinger Band width
     * @param closes    Recent close prices for additional analysis
     * @return MarketRegime with classification
     */
    public MarketRegime detect(String symbol, String timeframe,
                                double adx, double plusDI, double minusDI,
                                double rsi, double macdHist,
                                double atr, double atrPct, double bbWidth,
                                double[] closes) {

        // Determine trend strength from ADX
        TrendStrength trendStrength = classifyTrendStrength(adx);

        // Determine trend direction
        double trendDirection = plusDI > minusDI ? 1 : plusDI < minusDI ? -1 : 0;

        // Determine volatility state
        VolatilityState volatilityState = classifyVolatility(atrPct, bbWidth);

        // Determine momentum state
        MomentumState momentumState = classifyMomentum(rsi, macdHist);
        double momentumScore = calculateMomentumScore(rsi, macdHist);

        // Classify regime type
        RegimeType regimeType = classifyRegime(trendStrength, trendDirection, volatilityState, momentumState);

        // Check for regime transition
        MarketRegime prevRegime = currentRegimes.get(symbol);
        boolean isTransitioning = false;
        RegimeType likelyNextRegime = null;
        int regimeAge = 1;

        if (prevRegime != null) {
            if (prevRegime.getRegimeType() == regimeType) {
                regimeAge = prevRegime.getRegimeAge() + 1;
            } else {
                isTransitioning = true;
                likelyNextRegime = predictNextRegime(prevRegime.getRegimeType(), regimeType);
            }
        }

        // Calculate confidence
        double confidence = calculateConfidence(adx, rsi, bbWidth, regimeAge);

        // Determine trading mode
        TradingMode tradingMode = recommendTradingMode(regimeType, volatilityState, confidence);

        // Get suitable strategies
        String[] suitableStrategies = getSuitableStrategies(regimeType, volatilityState);
        String[] avoidStrategies = getAvoidStrategies(regimeType, volatilityState);

        // Calculate expected range
        double expectedRange = atr * 2;  // Simplified: 2 ATR expected range

        MarketRegime regime = MarketRegime.builder()
            .symbol(symbol)
            .timestamp(Instant.now())
            .timeframe(timeframe)
            // Regime classification
            .regimeType(regimeType)
            .trendStrength(trendStrength)
            .volatilityState(volatilityState)
            .momentumState(momentumState)
            // Trend metrics
            .adxValue(adx)
            .plusDI(plusDI)
            .minusDI(minusDI)
            .trendDirection(trendDirection)
            .trendDuration(regimeAge)
            // Volatility metrics
            .atr(atr)
            .atrPercent(atrPct)
            .bbWidth(bbWidth)
            .volatilityPercentile(calculateVolatilityPercentile(atrPct))
            // Momentum metrics
            .rsi(rsi)
            .macdHistogram(macdHist)
            .momentumScore(momentumScore)
            // Regime quality
            .regimeConfidence(confidence)
            .regimeAge(regimeAge)
            .isTransitioning(isTransitioning)
            .likelyNextRegime(likelyNextRegime)
            // Trading implications
            .recommendedMode(tradingMode)
            .expectedRange(expectedRange)
            .suitableStrategies(suitableStrategies)
            .avoidStrategies(avoidStrategies)
            .build();

        // Update cache
        currentRegimes.put(symbol, regime);
        updateRegimeHistory(symbol, regimeType);

        return regime;
    }

    /**
     * Simplified detection with fewer inputs.
     */
    public MarketRegime detectSimple(String symbol, String timeframe,
                                      double adx, boolean trendUp,
                                      double rsi, double atrPct) {
        return detect(symbol, timeframe,
            adx,
            trendUp ? 30 : 20,
            trendUp ? 20 : 30,
            rsi,
            rsi > 50 ? 1 : -1,
            atrPct,
            atrPct,
            atrPct * 10,
            new double[0]);
    }

    private TrendStrength classifyTrendStrength(double adx) {
        if (adx >= ADX_STRONG_TREND) return TrendStrength.VERY_STRONG;
        if (adx >= ADX_TREND) return TrendStrength.STRONG;
        if (adx >= ADX_MODERATE_TREND) return TrendStrength.MODERATE;
        if (adx >= ADX_WEAK) return TrendStrength.WEAK;
        return TrendStrength.ABSENT;
    }

    private VolatilityState classifyVolatility(double atrPct, double bbWidth) {
        // Combined ATR% and BB width for volatility classification
        double volScore = (atrPct * 50) + (bbWidth / 10);

        if (volScore > 4) return VolatilityState.VERY_HIGH;
        if (volScore > 3) return VolatilityState.HIGH;
        if (volScore > 1.5) return VolatilityState.NORMAL;
        if (volScore > 0.8) return VolatilityState.LOW;
        return VolatilityState.VERY_LOW;
    }

    private MomentumState classifyMomentum(double rsi, double macdHist) {
        boolean rsiBullish = rsi > RSI_NEUTRAL_UPPER;
        boolean rsiBearish = rsi < RSI_NEUTRAL_LOWER;
        boolean rsiExtremeBullish = rsi > RSI_OVERBOUGHT;
        boolean rsiExtremeBearish = rsi < RSI_OVERSOLD;
        boolean macdBullish = macdHist > 0;
        boolean macdBearish = macdHist < 0;

        if (rsiExtremeBullish || (rsiBullish && macdBullish)) {
            return MomentumState.STRONG_BULLISH;
        }
        if (rsiBullish || macdBullish) {
            return MomentumState.BULLISH;
        }
        if (rsiExtremeBearish || (rsiBearish && macdBearish)) {
            return MomentumState.STRONG_BEARISH;
        }
        if (rsiBearish || macdBearish) {
            return MomentumState.BEARISH;
        }
        return MomentumState.NEUTRAL;
    }

    private double calculateMomentumScore(double rsi, double macdHist) {
        // RSI contribution: -50 to +50
        double rsiScore = (rsi - 50);

        // MACD contribution: normalize to -50 to +50
        double macdScore = Math.max(-50, Math.min(50, macdHist * 10));

        return rsiScore + macdScore;
    }

    private RegimeType classifyRegime(TrendStrength strength, double direction,
                                       VolatilityState volatility, MomentumState momentum) {

        boolean hasTrend = strength != TrendStrength.ABSENT && strength != TrendStrength.WEAK;
        boolean isUp = direction > 0;
        boolean strongMomentum = momentum == MomentumState.STRONG_BULLISH ||
                                  momentum == MomentumState.STRONG_BEARISH;

        // Trending regimes
        if (hasTrend) {
            if (isUp) {
                return strongMomentum ? RegimeType.STRONG_UPTREND : RegimeType.WEAK_UPTREND;
            } else {
                return strongMomentum ? RegimeType.STRONG_DOWNTREND : RegimeType.WEAK_DOWNTREND;
            }
        }

        // Check for breakout/breakdown
        if (volatility == VolatilityState.VERY_HIGH || volatility == VolatilityState.HIGH) {
            if (momentum == MomentumState.STRONG_BULLISH) return RegimeType.BREAKOUT;
            if (momentum == MomentumState.STRONG_BEARISH) return RegimeType.BREAKDOWN;
        }

        // Non-trending regimes
        if (volatility == VolatilityState.VERY_LOW || volatility == VolatilityState.LOW) {
            return RegimeType.RANGING;  // Low volatility consolidation
        }

        if (volatility == VolatilityState.VERY_HIGH) {
            return RegimeType.CHOPPY;  // High volatility without trend
        }

        return RegimeType.RANGING;
    }

    private double calculateConfidence(double adx, double rsi, double bbWidth, int regimeAge) {
        double confidence = 50;  // Base confidence

        // ADX contribution
        if (adx > 30) confidence += 15;
        else if (adx > 20) confidence += 10;
        else if (adx < 15) confidence -= 10;

        // RSI contribution (clearer signals at extremes)
        if (rsi > 70 || rsi < 30) confidence += 10;
        else if (rsi > 60 || rsi < 40) confidence += 5;

        // Regime age contribution
        confidence += Math.min(15, regimeAge * 1.5);

        return Math.max(0, Math.min(100, confidence));
    }

    private RegimeType predictNextRegime(RegimeType current, RegimeType detected) {
        // Simple transition logic
        if (current == RegimeType.RANGING) {
            if (detected == RegimeType.BREAKOUT || detected == RegimeType.STRONG_UPTREND) {
                return RegimeType.STRONG_UPTREND;
            }
            if (detected == RegimeType.BREAKDOWN || detected == RegimeType.STRONG_DOWNTREND) {
                return RegimeType.STRONG_DOWNTREND;
            }
        }

        if (current == RegimeType.STRONG_UPTREND || current == RegimeType.STRONG_DOWNTREND) {
            if (detected == RegimeType.WEAK_UPTREND || detected == RegimeType.WEAK_DOWNTREND) {
                return RegimeType.RANGING;  // Trend weakening, likely to range
            }
        }

        return detected;
    }

    private TradingMode recommendTradingMode(RegimeType regime, VolatilityState volatility, double confidence) {
        if (confidence < 50 || volatility == VolatilityState.VERY_HIGH && regime == RegimeType.CHOPPY) {
            return TradingMode.AVOID;
        }

        switch (regime) {
            case STRONG_UPTREND:
            case STRONG_DOWNTREND:
                return TradingMode.TREND_FOLLOWING;
            case WEAK_UPTREND:
            case WEAK_DOWNTREND:
                return confidence > 60 ? TradingMode.TREND_FOLLOWING : TradingMode.MEAN_REVERSION;
            case RANGING:
                return volatility == VolatilityState.VERY_LOW ? TradingMode.BREAKOUT : TradingMode.MEAN_REVERSION;
            case BREAKOUT:
            case BREAKDOWN:
                return TradingMode.BREAKOUT;
            case CHOPPY:
            default:
                return TradingMode.AVOID;
        }
    }

    private String[] getSuitableStrategies(RegimeType regime, VolatilityState volatility) {
        switch (regime) {
            case STRONG_UPTREND:
            case STRONG_DOWNTREND:
                return new String[]{"Trend Following", "Momentum", "Breakout Continuation"};
            case WEAK_UPTREND:
            case WEAK_DOWNTREND:
                return new String[]{"Pullback Entry", "Trend Continuation"};
            case RANGING:
                if (volatility == VolatilityState.VERY_LOW) {
                    return new String[]{"Range Breakout", "Squeeze Play"};
                }
                return new String[]{"Range Trading", "Mean Reversion", "Support/Resistance"};
            case BREAKOUT:
            case BREAKDOWN:
                return new String[]{"Breakout", "Momentum", "Volume Surge"};
            case CHOPPY:
            default:
                return new String[]{"Scalping", "Very Short Term Only"};
        }
    }

    private String[] getAvoidStrategies(RegimeType regime, VolatilityState volatility) {
        switch (regime) {
            case STRONG_UPTREND:
                return new String[]{"Shorting", "Mean Reversion Long"};
            case STRONG_DOWNTREND:
                return new String[]{"Going Long", "Buy the Dip"};
            case RANGING:
                return new String[]{"Trend Following", "Momentum"};
            case CHOPPY:
                return new String[]{"Swing Trading", "Position Trading", "Trend Following"};
            default:
                return new String[]{};
        }
    }

    private double calculateVolatilityPercentile(double atrPct) {
        // Simplified percentile calculation
        if (atrPct > 3) return 95;
        if (atrPct > 2) return 80;
        if (atrPct > 1.5) return 65;
        if (atrPct > 1) return 50;
        if (atrPct > 0.5) return 30;
        return 15;
    }

    private void updateRegimeHistory(String symbol, RegimeType regime) {
        List<RegimeType> history = regimeHistory.computeIfAbsent(symbol, k -> new ArrayList<>());
        history.add(regime);
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
    }

    // ==================== PUBLIC GETTERS ====================

    public MarketRegime getCurrentRegime(String symbol) {
        return currentRegimes.get(symbol);
    }

    public boolean isTrending(String symbol) {
        MarketRegime regime = currentRegimes.get(symbol);
        return regime != null && regime.isTrending();
    }

    public boolean isRanging(String symbol) {
        MarketRegime regime = currentRegimes.get(symbol);
        return regime != null && regime.isRanging();
    }

    public boolean isTradeable(String symbol) {
        MarketRegime regime = currentRegimes.get(symbol);
        return regime != null && regime.isTradeable();
    }

    public TradingMode getRecommendedMode(String symbol) {
        MarketRegime regime = currentRegimes.get(symbol);
        return regime != null ? regime.getRecommendedMode() : TradingMode.AVOID;
    }
}
