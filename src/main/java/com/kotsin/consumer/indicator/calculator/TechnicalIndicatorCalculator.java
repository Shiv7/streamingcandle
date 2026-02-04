package com.kotsin.consumer.indicator.calculator;

import com.kotsin.consumer.indicator.model.TechnicalIndicators;
import com.kotsin.consumer.indicator.model.TechnicalIndicators.*;
import com.kotsin.consumer.model.UnifiedCandle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * TechnicalIndicatorCalculator - Calculates all technical indicators.
 *
 * Supports:
 * - Moving Averages (SMA, EMA, VWMA)
 * - RSI (Relative Strength Index)
 * - MACD (Moving Average Convergence Divergence)
 * - Bollinger Bands
 * - ATR (Average True Range)
 * - SuperTrend
 * - ADX (Average Directional Index)
 * - Stochastic Oscillator
 * - Pivot Points
 */
@Component
@Slf4j
public class TechnicalIndicatorCalculator {

    // SuperTrend multiplier
    private static final double SUPERTREND_MULTIPLIER = 3.0;
    private static final int SUPERTREND_PERIOD = 10;

    /**
     * Calculate all technical indicators from raw price arrays.
     *
     * @param symbol Symbol name
     * @param timeframe Timeframe label
     * @param closes Close prices (most recent first)
     * @param highs High prices
     * @param lows Low prices
     * @param volumes Volume data
     * @return TechnicalIndicators with all values
     */
    public TechnicalIndicators calculateAll(String symbol, String timeframe,
                                            double[] closes, double[] highs,
                                            double[] lows, double[] volumes) {
        if (closes == null || closes.length == 0) {
            return TechnicalIndicators.builder().build();
        }

        // Convert arrays to UnifiedCandle list for reuse of existing methods
        List<UnifiedCandle> candles = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            UnifiedCandle candle = UnifiedCandle.builder()
                .symbol(symbol)
                .close(closes[i])
                .high(highs != null && i < highs.length ? highs[i] : closes[i])
                .low(lows != null && i < lows.length ? lows[i] : closes[i])
                .open(closes[i])  // Use close as open if not available
                .volume(volumes != null && i < volumes.length ? (long) volumes[i] : 0)
                .build();
            candles.add(candle);
        }

        // Calculate using existing method
        TechnicalIndicators indicators = calculate(candles);

        // Add volume data
        if (volumes != null && volumes.length > 0) {
            double currentVolume = volumes[0];
            double avgVolume = calculateAverageVolume(volumes, 20);

            // Use builder to add volume data (create new instance with volume)
            return TechnicalIndicators.builder()
                .symbol(indicators.getSymbol())
                .timestamp(indicators.getTimestamp())
                // Moving Averages
                .ema9(indicators.getEma9())
                .ema21(indicators.getEma21())
                .ema50(indicators.getEma50())
                .ema200(indicators.getEma200())
                .sma20(indicators.getSma20())
                .sma50(indicators.getSma50())
                .sma200(indicators.getSma200())
                .vwma20(indicators.getVwma20())
                // RSI
                .rsi14(indicators.getRsi14())
                .rsiSmoothed(indicators.getRsiSmoothed())
                .rsiZone(indicators.getRsiZone())
                // MACD
                .macdLine(indicators.getMacdLine())
                .macdSignal(indicators.getMacdSignal())
                .macdHistogram(indicators.getMacdHistogram())
                .macdCrossover(indicators.getMacdCrossover())
                // Bollinger Bands
                .bbUpper(indicators.getBbUpper())
                .bbMiddle(indicators.getBbMiddle())
                .bbLower(indicators.getBbLower())
                .bbWidth(indicators.getBbWidth())
                .bbPercentB(indicators.getBbPercentB())
                .bbPosition(indicators.getBbPosition())
                // ATR
                .atr14(indicators.getAtr14())
                .atrPercent(indicators.getAtrPercent())
                .atrMultiple(indicators.getAtrMultiple())
                // SuperTrend
                .superTrend(indicators.getSuperTrend())
                .superTrendDirection(indicators.getSuperTrendDirection())
                .superTrendFlip(indicators.isSuperTrendFlip())
                // ADX
                .adx14(indicators.getAdx14())
                .plusDI(indicators.getPlusDI())
                .minusDI(indicators.getMinusDI())
                .trendStrength(indicators.getTrendStrength())
                // Stochastic
                .stochK(indicators.getStochK())
                .stochD(indicators.getStochD())
                .stochZone(indicators.getStochZone())
                // Pivot Points
                .pivotPoint(indicators.getPivotPoint())
                .r1(indicators.getR1()).r2(indicators.getR2()).r3(indicators.getR3())
                .s1(indicators.getS1()).s2(indicators.getS2()).s3(indicators.getS3())
                // Volume
                .volume(currentVolume)
                .avgVolume20(avgVolume)
                .volumeRatio(avgVolume > 0 ? currentVolume / avgVolume : 1.0)
                // Derived signals
                .goldenCross(indicators.isGoldenCross())
                .deathCross(indicators.isDeathCross())
                .priceAboveEma21(indicators.isPriceAboveEma21())
                .priceAboveEma50(indicators.isPriceAboveEma50())
                .priceAboveSuperTrend(indicators.isPriceAboveSuperTrend())
                .macdBullish(indicators.isMacdBullish())
                .rsiBullish(indicators.isRsiBullish())
                .build();
        }

        return indicators;
    }

    /**
     * Calculate average volume over a period.
     */
    private double calculateAverageVolume(double[] volumes, int period) {
        if (volumes == null || volumes.length == 0) return 0;

        int count = Math.min(period, volumes.length);
        double sum = 0;
        for (int i = 0; i < count; i++) {
            sum += volumes[i];
        }
        return sum / count;
    }

    /**
     * Calculate all technical indicators for a candle.
     *
     * @param candles List of candles (most recent first)
     * @return TechnicalIndicators with all values
     */
    public TechnicalIndicators calculate(List<UnifiedCandle> candles) {
        if (candles == null || candles.isEmpty()) {
            return TechnicalIndicators.builder().build();
        }

        UnifiedCandle current = candles.get(0);
        double close = current.getClose();

        TechnicalIndicators.TechnicalIndicatorsBuilder builder = TechnicalIndicators.builder()
            .symbol(current.getSymbol())
            .timestamp(current.getTimestamp());

        // Moving Averages
        builder.ema9(calculateEMA(candles, 9));
        builder.ema21(calculateEMA(candles, 21));
        builder.ema50(calculateEMA(candles, 50));
        builder.ema200(calculateEMA(candles, 200));
        builder.sma20(calculateSMA(candles, 20));
        builder.sma50(calculateSMA(candles, 50));
        builder.sma200(calculateSMA(candles, 200));
        builder.vwma20(calculateVWMA(candles, 20));

        // RSI
        double rsi14 = calculateRSI(candles, 14);
        builder.rsi14(rsi14);
        builder.rsiSmoothed(calculateSmoothedRSI(candles, 14));
        builder.rsiZone(getRsiZone(rsi14));

        // MACD
        double ema12 = calculateEMA(candles, 12);
        double ema26 = calculateEMA(candles, 26);
        double macdLine = ema12 - ema26;
        double macdSignal = calculateMACDSignal(candles);
        double macdHistogram = macdLine - macdSignal;
        builder.macdLine(macdLine);
        builder.macdSignal(macdSignal);
        builder.macdHistogram(macdHistogram);
        builder.macdCrossover(getMacdSignal(candles, macdLine, macdSignal));

        // Bollinger Bands
        double sma20 = calculateSMA(candles, 20);
        double stdDev = calculateStdDev(candles, 20);
        double bbUpper = sma20 + 2 * stdDev;
        double bbLower = sma20 - 2 * stdDev;
        builder.bbUpper(bbUpper);
        builder.bbMiddle(sma20);
        builder.bbLower(bbLower);
        builder.bbWidth(sma20 > 0 ? (bbUpper - bbLower) / sma20 : 0);
        builder.bbPercentB((bbUpper - bbLower) > 0 ? (close - bbLower) / (bbUpper - bbLower) : 0.5);
        builder.bbPosition(getBollingerPosition(close, bbUpper, sma20, bbLower));

        // ATR
        double atr14 = calculateATR(candles, 14);
        builder.atr14(atr14);
        builder.atrPercent(close > 0 ? (atr14 / close) * 100 : 0);
        double currentRange = current.getHigh() - current.getLow();
        builder.atrMultiple(atr14 > 0 ? currentRange / atr14 : 0);

        // SuperTrend
        SuperTrendResult superTrend = calculateSuperTrend(candles);
        builder.superTrend(superTrend.value);
        builder.superTrendDirection(superTrend.direction);
        builder.superTrendFlip(superTrend.flip);

        // ADX
        ADXResult adx = calculateADX(candles, 14);
        builder.adx14(adx.adx);
        builder.plusDI(adx.plusDI);
        builder.minusDI(adx.minusDI);
        builder.trendStrength(getTrendStrength(adx.adx));

        // Stochastic
        StochasticResult stoch = calculateStochastic(candles, 14, 3);
        builder.stochK(stoch.k);
        builder.stochD(stoch.d);
        builder.stochZone(getStochZone(stoch.k));

        // Pivot Points
        PivotResult pivots = calculatePivotPoints(candles);
        builder.pivotPoint(pivots.pivot);
        builder.r1(pivots.r1).r2(pivots.r2).r3(pivots.r3);
        builder.s1(pivots.s1).s2(pivots.s2).s3(pivots.s3);

        // Derived signals
        double ema21 = calculateEMA(candles, 21);
        double ema50Current = calculateEMA(candles, 50);
        double ema200Current = calculateEMA(candles, 200);

        builder.priceAboveEma21(close > ema21);
        builder.priceAboveEma50(close > ema50Current);
        builder.priceAboveSuperTrend(close > superTrend.value);
        builder.macdBullish(macdLine > macdSignal);
        builder.rsiBullish(rsi14 > 50 && rsi14 < 70);

        // Golden/Death cross (check previous values)
        if (candles.size() >= 2) {
            List<UnifiedCandle> prevCandles = candles.subList(1, candles.size());
            double prevEma50 = calculateEMA(prevCandles, 50);
            double prevEma200 = calculateEMA(prevCandles, 200);

            boolean wasBelow = prevEma50 < prevEma200;
            boolean nowAbove = ema50Current > ema200Current;
            builder.goldenCross(wasBelow && nowAbove);
            builder.deathCross(!wasBelow && !nowAbove && prevEma50 > prevEma200);
        }

        return builder.build();
    }

    // ==================== MOVING AVERAGES ====================

    private double calculateSMA(List<UnifiedCandle> candles, int period) {
        if (candles.size() < period) return 0;

        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += candles.get(i).getClose();
        }
        return sum / period;
    }

    private double calculateEMA(List<UnifiedCandle> candles, int period) {
        if (candles.size() < period) return 0;

        double multiplier = 2.0 / (period + 1);

        // Start with SMA for first value
        double sum = 0;
        for (int i = period - 1; i < Math.min(period * 2 - 1, candles.size()); i++) {
            sum += candles.get(i).getClose();
        }
        double ema = sum / period;

        // Calculate EMA from oldest to newest
        for (int i = period - 2; i >= 0; i--) {
            double close = candles.get(i).getClose();
            ema = (close - ema) * multiplier + ema;
        }

        return ema;
    }

    private double calculateVWMA(List<UnifiedCandle> candles, int period) {
        if (candles.size() < period) return 0;

        double sumPV = 0;  // Price * Volume
        double sumV = 0;   // Volume

        for (int i = 0; i < period; i++) {
            UnifiedCandle c = candles.get(i);
            sumPV += c.getClose() * c.getVolume();
            sumV += c.getVolume();
        }

        return sumV > 0 ? sumPV / sumV : 0;
    }

    // ==================== RSI ====================

    private double calculateRSI(List<UnifiedCandle> candles, int period) {
        if (candles.size() < period + 1) return 50;

        double avgGain = 0;
        double avgLoss = 0;

        // Initial average
        for (int i = 0; i < period; i++) {
            double change = candles.get(i).getClose() - candles.get(i + 1).getClose();
            if (change > 0) avgGain += change;
            else avgLoss += Math.abs(change);
        }
        avgGain /= period;
        avgLoss /= period;

        if (avgLoss == 0) return 100;

        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    private double calculateSmoothedRSI(List<UnifiedCandle> candles, int period) {
        // Wilder's smoothed RSI
        if (candles.size() < period * 2) return calculateRSI(candles, period);

        double avgGain = 0;
        double avgLoss = 0;

        // Initial values
        for (int i = period; i < period * 2; i++) {
            double change = candles.get(i - 1).getClose() - candles.get(i).getClose();
            if (change > 0) avgGain += change;
            else avgLoss += Math.abs(change);
        }
        avgGain /= period;
        avgLoss /= period;

        // Smooth
        for (int i = period - 1; i >= 0; i--) {
            double change = candles.get(i).getClose() - candles.get(i + 1).getClose();
            if (change > 0) {
                avgGain = (avgGain * (period - 1) + change) / period;
                avgLoss = (avgLoss * (period - 1)) / period;
            } else {
                avgGain = (avgGain * (period - 1)) / period;
                avgLoss = (avgLoss * (period - 1) + Math.abs(change)) / period;
            }
        }

        if (avgLoss == 0) return 100;
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    private RsiZone getRsiZone(double rsi) {
        if (rsi < 30) return RsiZone.OVERSOLD;
        if (rsi > 70) return RsiZone.OVERBOUGHT;
        return RsiZone.NEUTRAL;
    }

    // ==================== MACD ====================

    private double calculateMACDSignal(List<UnifiedCandle> candles) {
        // Calculate MACD line for last 9 periods and get EMA
        if (candles.size() < 35) return 0;  // Need 26 + 9

        double[] macdValues = new double[9];
        for (int i = 0; i < 9; i++) {
            List<UnifiedCandle> subset = candles.subList(i, candles.size());
            double ema12 = calculateEMA(subset, 12);
            double ema26 = calculateEMA(subset, 26);
            macdValues[i] = ema12 - ema26;
        }

        // EMA of MACD values
        double multiplier = 2.0 / 10.0;
        double ema = macdValues[8];  // Start from oldest
        for (int i = 7; i >= 0; i--) {
            ema = (macdValues[i] - ema) * multiplier + ema;
        }
        return ema;
    }

    private MacdSignal getMacdSignal(List<UnifiedCandle> candles, double macdLine, double macdSignal) {
        if (candles.size() < 36) return MacdSignal.NEUTRAL;

        // Check previous MACD
        List<UnifiedCandle> prevCandles = candles.subList(1, candles.size());
        double prevEma12 = calculateEMA(prevCandles, 12);
        double prevEma26 = calculateEMA(prevCandles, 26);
        double prevMacd = prevEma12 - prevEma26;
        double prevSignal = calculateMACDSignal(prevCandles);

        boolean wasBelow = prevMacd < prevSignal;
        boolean isAbove = macdLine > macdSignal;

        if (wasBelow && isAbove) return MacdSignal.BULLISH_CROSS;
        if (!wasBelow && !isAbove) return MacdSignal.BEARISH_CROSS;
        if (isAbove) return MacdSignal.BULLISH;
        return MacdSignal.BEARISH;
    }

    // ==================== BOLLINGER BANDS ====================

    private double calculateStdDev(List<UnifiedCandle> candles, int period) {
        if (candles.size() < period) return 0;

        double mean = calculateSMA(candles, period);
        double sumSquares = 0;

        for (int i = 0; i < period; i++) {
            double diff = candles.get(i).getClose() - mean;
            sumSquares += diff * diff;
        }

        return Math.sqrt(sumSquares / period);
    }

    private BollingerPosition getBollingerPosition(double close, double upper, double middle, double lower) {
        if (close > upper) return BollingerPosition.ABOVE_UPPER;
        if (close < lower) return BollingerPosition.BELOW_LOWER;
        if (close > middle) {
            return (close - middle) > (upper - middle) * 0.5 ?
                BollingerPosition.UPPER_HALF : BollingerPosition.MIDDLE;
        }
        return (middle - close) > (middle - lower) * 0.5 ?
            BollingerPosition.LOWER_HALF : BollingerPosition.MIDDLE;
    }

    // ==================== ATR ====================

    private double calculateATR(List<UnifiedCandle> candles, int period) {
        if (candles.size() < period + 1) return 0;

        double atr = 0;

        for (int i = 0; i < period; i++) {
            UnifiedCandle current = candles.get(i);
            UnifiedCandle prev = candles.get(i + 1);

            double tr = Math.max(
                current.getHigh() - current.getLow(),
                Math.max(
                    Math.abs(current.getHigh() - prev.getClose()),
                    Math.abs(current.getLow() - prev.getClose())
                )
            );
            atr += tr;
        }

        return atr / period;
    }

    // ==================== SUPERTREND ====================

    private SuperTrendResult calculateSuperTrend(List<UnifiedCandle> candles) {
        SuperTrendResult result = new SuperTrendResult();

        if (candles.size() < SUPERTREND_PERIOD + 1) {
            result.value = candles.get(0).getClose();
            result.direction = SuperTrendDirection.UP;
            result.flip = false;
            return result;
        }

        double atr = calculateATR(candles, SUPERTREND_PERIOD);
        UnifiedCandle current = candles.get(0);
        double hl2 = (current.getHigh() + current.getLow()) / 2;

        double basicUpperBand = hl2 + SUPERTREND_MULTIPLIER * atr;
        double basicLowerBand = hl2 - SUPERTREND_MULTIPLIER * atr;

        // Get previous SuperTrend values
        List<UnifiedCandle> prevCandles = candles.subList(1, candles.size());
        double prevAtr = calculateATR(prevCandles, SUPERTREND_PERIOD);
        UnifiedCandle prev = candles.get(1);
        double prevHl2 = (prev.getHigh() + prev.getLow()) / 2;
        double prevUpperBand = prevHl2 + SUPERTREND_MULTIPLIER * prevAtr;
        double prevLowerBand = prevHl2 - SUPERTREND_MULTIPLIER * prevAtr;

        // Final bands
        double finalUpperBand = (basicUpperBand < prevUpperBand || prev.getClose() > prevUpperBand) ?
            basicUpperBand : prevUpperBand;
        double finalLowerBand = (basicLowerBand > prevLowerBand || prev.getClose() < prevLowerBand) ?
            basicLowerBand : prevLowerBand;

        // Determine direction
        boolean prevWasUp = prev.getClose() > prevLowerBand;
        boolean nowUp;

        if (prevWasUp) {
            nowUp = current.getClose() >= finalLowerBand;
        } else {
            nowUp = current.getClose() > finalUpperBand;
        }

        result.direction = nowUp ? SuperTrendDirection.UP : SuperTrendDirection.DOWN;
        result.value = nowUp ? finalLowerBand : finalUpperBand;
        result.flip = prevWasUp != nowUp;

        return result;
    }

    // ==================== ADX ====================

    private ADXResult calculateADX(List<UnifiedCandle> candles, int period) {
        ADXResult result = new ADXResult();

        if (candles.size() < period * 2) {
            return result;
        }

        double sumPlusDM = 0;
        double sumMinusDM = 0;
        double sumTR = 0;

        for (int i = 0; i < period; i++) {
            UnifiedCandle current = candles.get(i);
            UnifiedCandle prev = candles.get(i + 1);

            double highDiff = current.getHigh() - prev.getHigh();
            double lowDiff = prev.getLow() - current.getLow();

            double plusDM = (highDiff > lowDiff && highDiff > 0) ? highDiff : 0;
            double minusDM = (lowDiff > highDiff && lowDiff > 0) ? lowDiff : 0;

            double tr = Math.max(
                current.getHigh() - current.getLow(),
                Math.max(
                    Math.abs(current.getHigh() - prev.getClose()),
                    Math.abs(current.getLow() - prev.getClose())
                )
            );

            sumPlusDM += plusDM;
            sumMinusDM += minusDM;
            sumTR += tr;
        }

        result.plusDI = sumTR > 0 ? (sumPlusDM / sumTR) * 100 : 0;
        result.minusDI = sumTR > 0 ? (sumMinusDM / sumTR) * 100 : 0;

        double dx = (result.plusDI + result.minusDI) > 0 ?
            Math.abs(result.plusDI - result.minusDI) / (result.plusDI + result.minusDI) * 100 : 0;
        result.adx = dx;  // Simplified - should be smoothed

        return result;
    }

    private TrendStrength getTrendStrength(double adx) {
        if (adx > 25) return TrendStrength.STRONG;
        if (adx > 20) return TrendStrength.MODERATE;
        if (adx > 15) return TrendStrength.WEAK;
        return TrendStrength.NO_TREND;
    }

    // ==================== STOCHASTIC ====================

    private StochasticResult calculateStochastic(List<UnifiedCandle> candles, int kPeriod, int dPeriod) {
        StochasticResult result = new StochasticResult();

        if (candles.size() < kPeriod) return result;

        // Find highest high and lowest low
        double highestHigh = Double.MIN_VALUE;
        double lowestLow = Double.MAX_VALUE;

        for (int i = 0; i < kPeriod; i++) {
            highestHigh = Math.max(highestHigh, candles.get(i).getHigh());
            lowestLow = Math.min(lowestLow, candles.get(i).getLow());
        }

        double close = candles.get(0).getClose();
        result.k = (highestHigh - lowestLow) > 0 ?
            ((close - lowestLow) / (highestHigh - lowestLow)) * 100 : 50;

        // %D is SMA of %K
        if (candles.size() >= kPeriod + dPeriod) {
            double sumK = result.k;
            for (int i = 1; i < dPeriod; i++) {
                List<UnifiedCandle> subset = candles.subList(i, candles.size());
                StochasticResult prevK = calculateStochastic(subset, kPeriod, 1);
                sumK += prevK.k;
            }
            result.d = sumK / dPeriod;
        } else {
            result.d = result.k;
        }

        return result;
    }

    private StochZone getStochZone(double k) {
        if (k < 20) return StochZone.OVERSOLD;
        if (k > 80) return StochZone.OVERBOUGHT;
        return StochZone.NEUTRAL;
    }

    // ==================== PIVOT POINTS ====================

    private PivotResult calculatePivotPoints(List<UnifiedCandle> candles) {
        PivotResult result = new PivotResult();

        if (candles.isEmpty()) return result;

        // Use previous day's data (or previous candle for intraday)
        UnifiedCandle ref = candles.size() > 1 ? candles.get(1) : candles.get(0);

        double high = ref.getHigh();
        double low = ref.getLow();
        double close = ref.getClose();

        result.pivot = (high + low + close) / 3;
        result.r1 = 2 * result.pivot - low;
        result.s1 = 2 * result.pivot - high;
        result.r2 = result.pivot + (high - low);
        result.s2 = result.pivot - (high - low);
        result.r3 = result.r1 + (high - low);
        result.s3 = result.s1 - (high - low);

        return result;
    }

    // ==================== HELPER CLASSES ====================

    private static class SuperTrendResult {
        double value;
        SuperTrendDirection direction;
        boolean flip;
    }

    private static class ADXResult {
        double adx;
        double plusDI;
        double minusDI;
    }

    private static class StochasticResult {
        double k;
        double d;
    }

    private static class PivotResult {
        double pivot;
        double r1, r2, r3;
        double s1, s2, s3;
    }
}
