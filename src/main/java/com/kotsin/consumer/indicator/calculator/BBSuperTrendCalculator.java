package com.kotsin.consumer.indicator.calculator;

import com.kotsin.consumer.indicator.model.BBSuperTrend;
import com.kotsin.consumer.indicator.model.BBSuperTrend.*;
import com.kotsin.consumer.indicator.model.SuperTrendState;
import com.kotsin.consumer.indicator.repository.SuperTrendStateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
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
 *
 * FIX (2026-02-02): Implemented full series calculation to properly detect
 * SuperTrend flips. Previously, the calculator only computed for the last candle
 * and relied on persistent state, causing missed flip detection on first calculation.
 *
 * FIX (2026-02-02): Added SuperTrend state persistence to MongoDB.
 * This ensures trend direction is preserved across restarts, preventing false
 * flip detection when recalculating from scratch with limited historical data.
 */
@Component
@Slf4j
public class BBSuperTrendCalculator {

    // Default parameters
    private static final int BB_PERIOD = 20;
    private static final double BB_MULTIPLIER = 2.0;
    private static final int DEFAULT_atrPeriod = 7;  // Changed from 14 to 7 per user request
    private static final double ST_MULTIPLIER = 3.0;

    @Value("${fudkii.trigger.st.period:7}")
    private int atrPeriod;

    // State staleness threshold - if state is older than this, recalculate from scratch
    private static final Duration STATE_STALENESS_THRESHOLD = Duration.ofDays(7);

    // State tracking for volatility history (not used for flip detection anymore)
    private final Map<String, VolatilityHistory> volatilityHistoryMap = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private SuperTrendStateRepository stateRepository;

    /**
     * Calculate BBSuperTrend for given OHLC data.
     *
     * FIX: Now iterates through ALL candles to compute SuperTrend sequentially,
     * ensuring proper flip detection even on first calculation.
     *
     * FIX: Uses persisted SuperTrend state when available to ensure continuity
     * across application restarts.
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
        return calculate(symbol, timeframe, closes, highs, lows, null);
    }

    /**
     * Calculate BBSuperTrend with optional window start time for state persistence.
     * LEGACY METHOD - for backward compatibility. Use the overload with windowStarts for accurate state handling.
     *
     * @param symbol           Symbol identifier
     * @param timeframe        Timeframe
     * @param closes           Close prices (oldest to newest)
     * @param highs            High prices
     * @param lows             Low prices
     * @param lastWindowStart  Window start time of the last candle (for state persistence)
     * @return BBSuperTrend indicator values
     */
    public BBSuperTrend calculate(String symbol, String timeframe,
                                   double[] closes, double[] highs, double[] lows,
                                   Instant lastWindowStart) {
        // Delegate to the new method with null windowStarts (will calculate fresh without state)
        return calculate(symbol, timeframe, closes, highs, lows, null, lastWindowStart);
    }

    /**
     * Calculate BBSuperTrend with full window timestamp context for intelligent state handling.
     *
     * FIX (2026-02-02): This method properly validates persisted state against current data context.
     * It only applies state if the state's window exists in the current data array and the
     * persisted bands are reasonable for the current data.
     *
     * @param symbol           Symbol identifier
     * @param timeframe        Timeframe
     * @param closes           Close prices (oldest to newest)
     * @param highs            High prices
     * @param lows             Low prices
     * @param windowStarts     Window start timestamps for each candle (for state matching)
     * @param lastWindowStart  Window start time of the last candle (for state persistence)
     * @return BBSuperTrend indicator values
     */
    public BBSuperTrend calculate(String symbol, String timeframe,
                                   double[] closes, double[] highs, double[] lows,
                                   Instant[] windowStarts,
                                   Instant lastWindowStart) {

        int n = closes.length;
        int minRequired = Math.max(BB_PERIOD, atrPeriod) + 1;
        int recommendedMinimum = 50; // For proper ATR/SuperTrend warmup

        if (closes == null || n < minRequired) {
            log.debug("Insufficient data for {}: have {}, need {}", symbol, n, minRequired);
            return defaultBBST(symbol, timeframe);
        }

        // Warn if insufficient candles for accurate calculation
        if (n < recommendedMinimum) {
            log.warn("[BBST-CALC] {} Only {} candles available, recommended {} for accurate SuperTrend. " +
                "Results may differ from broker due to insufficient ATR warmup.",
                symbol, n, recommendedMinimum);
        }

        // ==================== LOAD PERSISTED STATE ====================
        SuperTrendState savedState = loadPersistedState(symbol, timeframe);
        boolean usingPersistedState = false;
        int persistedStateIndex = -1;

        // ==================== CALCULATE ATR FOR ALL CANDLES ====================
        double[] trueRanges = new double[n];
        double[] atrValues = new double[n];

        trueRanges[0] = highs[0] - lows[0];
        for (int i = 1; i < n; i++) {
            double hl = highs[i] - lows[i];
            double hc = Math.abs(highs[i] - closes[i - 1]);
            double lc = Math.abs(lows[i] - closes[i - 1]);
            trueRanges[i] = Math.max(hl, Math.max(hc, lc));
        }

        // Calculate ATR using RMA (Wilder's Moving Average) - standard for SuperTrend
        // RMA formula: RMA[i] = (RMA[i-1] * (period - 1) + TrueRange[i]) / period
        // First ATR value uses SMA as seed
        double sum = 0;
        for (int j = 0; j < atrPeriod; j++) {
            sum += trueRanges[j];
        }
        atrValues[atrPeriod - 1] = sum / atrPeriod;  // Seed with SMA

        // Subsequent values use RMA (Wilder's smoothing)
        for (int i = atrPeriod; i < n; i++) {
            atrValues[i] = (atrValues[i - 1] * (atrPeriod - 1) + trueRanges[i]) / atrPeriod;
        }

        // ==================== CALCULATE SUPERTREND FOR ALL CANDLES ====================
        double[] finalUpperBands = new double[n];
        double[] finalLowerBands = new double[n];
        double[] superTrendValues = new double[n];
        TrendDirection[] trends = new TrendDirection[n];

        // Start from atrPeriod where we have valid ATR
        int startIdx = atrPeriod - 1;

        // ==================== INTELLIGENT STATE APPLICATION ====================
        // FIX: Only apply persisted state if it matches our current data context
        if (savedState != null && savedState.getTrend() != null
            && savedState.getLastCandleWindowStart() != null) {

            boolean stateApplied = false;

            // Case 1: We have windowStarts array - find matching index
            if (windowStarts != null && windowStarts.length == n) {
                int matchingIndex = findMatchingIndex(windowStarts, savedState.getLastCandleWindowStart());

                if (matchingIndex >= 0 && matchingIndex < n) {
                    // State window exists in our data - validate bands are reasonable
                    if (matchingIndex >= atrPeriod - 1) {
                        double hl2AtMatch = (highs[matchingIndex] + lows[matchingIndex]) / 2;
                        double atrAtMatch = atrValues[matchingIndex];

                        // Calculate what bands SHOULD be approximately at this index
                        double expectedUpper = hl2AtMatch + ST_MULTIPLIER * atrAtMatch;

                        // Check if persisted upper band is within 10% of expected
                        double tolerance = 0.10;
                        double diff = Math.abs(savedState.getFinalUpperBand() - expectedUpper) / expectedUpper;

                        if (diff < tolerance) {
                            // Bands are reasonable - apply state at the matching index
                            trends[matchingIndex] = savedState.getTrend();
                            superTrendValues[matchingIndex] = savedState.getSuperTrendValue();
                            finalUpperBands[matchingIndex] = savedState.getFinalUpperBand();
                            finalLowerBands[matchingIndex] = savedState.getFinalLowerBand();

                            persistedStateIndex = matchingIndex;
                            usingPersistedState = true;
                            startIdx = matchingIndex + 1;
                            stateApplied = true;

                            log.info("[BBST-CALC] {} Applied persisted state at index {} (window={}): " +
                                "trend={}, ST={:.2f}, bandDiff={:.1f}%",
                                symbol, matchingIndex, savedState.getLastCandleWindowStart(),
                                savedState.getTrend(), savedState.getSuperTrendValue(), diff * 100);
                        } else {
                            // Bands don't match - data context changed significantly
                            log.warn("[BBST-CALC] {} Persisted state bands don't match current data. " +
                                "Expected upper ~{:.2f}, got {:.2f} (diff={:.1f}%). Calculating fresh.",
                                symbol, expectedUpper, savedState.getFinalUpperBand(), diff * 100);
                            clearState(symbol, timeframe);
                        }
                    } else {
                        log.debug("[BBST-CALC] {} State window at index {} is before ATR warmup ({}). Calculating fresh.",
                            symbol, matchingIndex, atrPeriod - 1);
                    }
                } else {
                    // State window NOT in our data array
                    log.warn("[BBST-CALC] {} Persisted state window {} not found in current data range " +
                        "[{} to {}]. Data context changed. Calculating fresh.",
                        symbol, savedState.getLastCandleWindowStart(),
                        windowStarts[0], windowStarts[n - 1]);
                    clearState(symbol, timeframe);
                }
            }
            // Case 2: No windowStarts array (legacy call) - use TREND DIRECTION ONLY
            else if (lastWindowStart != null) {
                // Only use trend direction, not band values (which may be from different context)
                if (savedState.getLastCandleWindowStart().isBefore(lastWindowStart)) {
                    // Apply only trend direction at startIdx, recalculate bands fresh
                    trends[startIdx] = savedState.getTrend();
                    // Calculate bands fresh at startIdx
                    double hl2 = (highs[startIdx] + lows[startIdx]) / 2;
                    double atr = atrValues[startIdx];
                    finalUpperBands[startIdx] = hl2 + ST_MULTIPLIER * atr;
                    finalLowerBands[startIdx] = hl2 - ST_MULTIPLIER * atr;
                    superTrendValues[startIdx] = savedState.getTrend() == TrendDirection.UP
                        ? finalLowerBands[startIdx] : finalUpperBands[startIdx];

                    persistedStateIndex = startIdx;
                    usingPersistedState = true;
                    startIdx++;
                    stateApplied = true;

                    log.info("[BBST-CALC] {} Using persisted TREND DIRECTION only (legacy call): trend={}, " +
                        "recalculated bands fresh. stateWindow={}, currentWindow={}",
                        symbol, savedState.getTrend(),
                        savedState.getLastCandleWindowStart(), lastWindowStart);
                } else {
                    log.debug("[BBST-CALC] {} Skipping persisted state - same or newer window", symbol);
                }
            }

            if (!stateApplied) {
                log.info("[BBST-CALC] {} Calculating SuperTrend fresh from scratch (no valid state context)", symbol);
            }
        } else if (savedState == null) {
            log.debug("[BBST-CALC] {} No persisted state found, calculating fresh", symbol);
        }

        // Calculate SuperTrend for remaining candles
        for (int i = startIdx; i < n; i++) {
            double close = closes[i];
            double high = highs[i];
            double low = lows[i];
            double atr = atrValues[i];

            // Calculate basic bands
            double hl2 = (high + low) / 2;
            double basicUpperBand = hl2 + ST_MULTIPLIER * atr;
            double basicLowerBand = hl2 - ST_MULTIPLIER * atr;

            // Apply band smoothing logic
            double finalUpperBand = basicUpperBand;
            double finalLowerBand = basicLowerBand;

            if (i > atrPeriod - 1 && finalUpperBands[i - 1] > 0) {
                // Smooth upper band: only lower it if previous close was below previous upper band
                if (closes[i - 1] <= finalUpperBands[i - 1]) {
                    finalUpperBand = Math.min(basicUpperBand, finalUpperBands[i - 1]);
                }
                // Smooth lower band: only raise it if previous close was above previous lower band
                if (closes[i - 1] >= finalLowerBands[i - 1]) {
                    finalLowerBand = Math.max(basicLowerBand, finalLowerBands[i - 1]);
                }
            }

            finalUpperBands[i] = finalUpperBand;
            finalLowerBands[i] = finalLowerBand;

            // Determine trend direction
            TrendDirection trend;
            double superTrend;

            if (i == atrPeriod - 1 && !usingPersistedState) {
                // First candle with no persisted state: initialize based on close vs mid-point
                double midPoint = (finalUpperBand + finalLowerBand) / 2;
                trend = close > midPoint ? TrendDirection.UP : TrendDirection.DOWN;
                superTrend = trend == TrendDirection.UP ? finalLowerBand : finalUpperBand;
            } else if (trends[i - 1] != null) {
                TrendDirection prevTrend = trends[i - 1];

                if (prevTrend == TrendDirection.UP) {
                    // Was UP: flip to DOWN if close breaks below previous lower band
                    if (close < finalLowerBands[i - 1]) {
                        trend = TrendDirection.DOWN;
                        superTrend = finalUpperBand;
                    } else {
                        trend = TrendDirection.UP;
                        superTrend = finalLowerBand;
                    }
                } else {
                    // Was DOWN: flip to UP if close breaks above previous upper band
                    if (close > finalUpperBands[i - 1]) {
                        trend = TrendDirection.UP;
                        superTrend = finalLowerBand;
                    } else {
                        trend = TrendDirection.DOWN;
                        superTrend = finalUpperBand;
                    }
                }
            } else {
                // Fallback: initialize based on close vs mid-point
                double midPoint = (finalUpperBand + finalLowerBand) / 2;
                trend = close > midPoint ? TrendDirection.UP : TrendDirection.DOWN;
                superTrend = trend == TrendDirection.UP ? finalLowerBand : finalUpperBand;
            }

            trends[i] = trend;
            superTrendValues[i] = superTrend;
        }

        // ==================== EXTRACT FINAL VALUES (LAST CANDLE) ====================
        int lastIdx = n - 1;
        double close = closes[lastIdx];
        double high = highs[lastIdx];
        double low = lows[lastIdx];
        double atr = atrValues[lastIdx];
        double superTrend = superTrendValues[lastIdx];
        double finalUpperBand = finalUpperBands[lastIdx];
        double finalLowerBand = finalLowerBands[lastIdx];
        TrendDirection trend = trends[lastIdx];

        // ==================== DETECT TREND CHANGE (FLIP) ====================
        // Compare last candle's trend with second-to-last candle's trend
        boolean trendChanged = false;
        int barsInTrend = 1;
        TrendDirection previousTrend = null;

        if (lastIdx > atrPeriod - 1 && trends[lastIdx - 1] != null) {
            previousTrend = trends[lastIdx - 1];
            trendChanged = previousTrend != trend;

            if (!trendChanged) {
                // Count bars in current trend
                for (int i = lastIdx - 1; i >= atrPeriod - 1; i--) {
                    if (trends[i] == trend) {
                        barsInTrend++;
                    } else {
                        break;
                    }
                }
            }
        }

        // FIX: If no previous trend was available (first calculation without persisted state),
        // check if we can detect a flip by looking at where the trend would have been
        // based on historical candle data. This helps catch flips on first calculation.
        if (previousTrend == null && !usingPersistedState && lastIdx >= atrPeriod) {
            // No previous trend means this is first calculation without state.
            // Look at the trend we calculated at the second-to-last position
            // to see if there's an implicit flip.
            // This won't detect real flips from before this batch, but at least
            // provides some continuity within the batch.
            log.info("[BBST-CALC] {} First calculation without persisted state. " +
                "Trend initialized to {} at close={}. Cannot detect flip from prior session.",
                symbol, trend, String.format("%.2f", close));
        }

        // Log flip detection for debugging
        if (trendChanged) {
            log.info("[BBST-CALC] {} TREND FLIP DETECTED: {} -> {} at candle {} (persistedState={}, prevTrend={})",
                symbol, previousTrend, trend, lastIdx, usingPersistedState, previousTrend);
        } else if (log.isDebugEnabled()) {
            log.debug("[BBST-CALC] {} Trend continuation: {} (barsInTrend={}, persistedState={})",
                symbol, trend, barsInTrend, usingPersistedState);
        }

        // ==================== SAVE STATE FOR PERSISTENCE ====================
        if (lastWindowStart != null) {
            saveState(symbol, timeframe, trend, superTrend, finalUpperBand, finalLowerBand,
                      atr, close, high, low, lastWindowStart, barsInTrend, trendChanged);
        }

        // ==================== CALCULATE BOLLINGER BANDS (LAST CANDLE) ====================
        double sma = calculateSMAAt(closes, BB_PERIOD, lastIdx);
        double stdDev = calculateStdDevAt(closes, BB_PERIOD, sma, lastIdx);
        double bbUpper = sma + BB_MULTIPLIER * stdDev;
        double bbLower = sma - BB_MULTIPLIER * stdDev;
        double bbWidth = sma > 0 ? (bbUpper - bbLower) / sma * 100 : 0;
        double percentB = (bbUpper - bbLower) > 0 ? (close - bbLower) / (bbUpper - bbLower) : 0.5;

        // ==================== BB SUPERTREND COMBINATION ====================
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

        // ==================== VOLATILITY ANALYSIS ====================
        String key = symbol + "_" + timeframe;
        VolatilityHistory volHistory = volatilityHistoryMap.computeIfAbsent(key, k -> new VolatilityHistory());

        volHistory.bbWidthHistory.add(bbWidth);
        if (volHistory.bbWidthHistory.size() > 20) {
            volHistory.bbWidthHistory.remove(0);
        }

        double avgBbWidth = volHistory.bbWidthHistory.stream().mapToDouble(d -> d).average().orElse(bbWidth);
        VolatilityState volatilityState = determineVolatilityState(bbWidth, avgBbWidth,
            volHistory.prevBbWidth > 0 ? volHistory.prevBbWidth : bbWidth);

        boolean isSqueezing = bbWidth < avgBbWidth * 0.7;
        boolean isExpanding = bbWidth > avgBbWidth * 1.3;

        volHistory.prevBbWidth = bbWidth;

        // ==================== PRICE POSITION & OTHER METRICS ====================
        PricePosition pricePosition = determinePricePosition(close, bbUpper, bbLower, sma);
        double trendStrength = calculateTrendStrength(close, superTrend, bbUpper, bbLower, percentB, trend);

        boolean touchedUpper = high >= bbUpper;
        boolean touchedLower = low <= bbLower;

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

    /**
     * Load persisted SuperTrend state from MongoDB.
     */
    private SuperTrendState loadPersistedState(String symbol, String timeframe) {
        if (stateRepository == null) {
            log.debug("[BBST-CALC] {} State repository not available, calculating from scratch", symbol);
            return null;
        }

        try {
            Optional<SuperTrendState> stateOpt = stateRepository.findByScripCodeAndTimeframe(symbol, timeframe);
            if (stateOpt.isPresent()) {
                SuperTrendState state = stateOpt.get();

                // Check if state is stale
                if (state.isStale(STATE_STALENESS_THRESHOLD)) {
                    log.info("[BBST-CALC] {} Persisted state is STALE (age > {}), recalculating from scratch. " +
                        "Last update was at {}, trend was {}",
                        symbol, STATE_STALENESS_THRESHOLD, state.getLastUpdated(), state.getTrend());
                    return null;
                }

                log.debug("[BBST-CALC] {} Found persisted state: trend={}, ST={}, lastWindow={}, lastUpdate={}",
                    symbol, state.getTrend(),
                    String.format("%.2f", state.getSuperTrendValue()),
                    state.getLastCandleWindowStart(), state.getLastUpdated());

                return state;
            } else {
                log.debug("[BBST-CALC] {} No persisted state found in MongoDB, will calculate from scratch", symbol);
            }
        } catch (Exception e) {
            log.warn("[BBST-CALC] {} Error loading persisted state: {}", symbol, e.getMessage());
        }

        return null;
    }

    /**
     * Save SuperTrend state to MongoDB for persistence.
     */
    private void saveState(String symbol, String timeframe, TrendDirection trend,
                           double superTrendValue, double finalUpperBand, double finalLowerBand,
                           double atr, double close, double high, double low,
                           Instant windowStart, int barsInTrend, boolean trendChanged) {
        if (stateRepository == null) {
            return;
        }

        try {
            SuperTrendState state = SuperTrendState.builder()
                .scripCode(symbol)
                .timeframe(timeframe)
                .lastUpdated(Instant.now())
                .trend(trend)
                .superTrendValue(superTrendValue)
                .finalUpperBand(finalUpperBand)
                .finalLowerBand(finalLowerBand)
                .lastAtr(atr)
                .lastCandleWindowStart(windowStart)
                .lastClose(close)
                .lastHigh(high)
                .lastLow(low)
                .barsInCurrentTrend(barsInTrend)
                .lastFlipTime(trendChanged ? Instant.now() : null)
                .build();

            // Check if existing state exists - copy id for update (not insert) and preserve flip time
            Optional<SuperTrendState> existingOpt = stateRepository.findByScripCodeAndTimeframe(symbol, timeframe);
            if (existingOpt.isPresent()) {
                SuperTrendState existing = existingOpt.get();
                state.setId(existing.getId());  // Copy _id to enable update instead of insert
                if (!trendChanged) {
                    state.setLastFlipTime(existing.getLastFlipTime());
                }
            }

            stateRepository.save(state);
            log.debug("[BBST-CALC] {} Saved state: trend={}, ST={}, windowStart={}, trendChanged={}",
                symbol, trend, String.format("%.2f", superTrendValue), windowStart, trendChanged);

        } catch (Exception e) {
            log.warn("[BBST-CALC] {} Error saving state: {}", symbol, e.getMessage());
        }
    }

    /**
     * Find the index in windowStarts array that matches the given timestamp.
     * @return matching index, or -1 if not found
     */
    private int findMatchingIndex(Instant[] windowStarts, Instant targetWindow) {
        if (windowStarts == null || targetWindow == null) {
            return -1;
        }
        for (int i = 0; i < windowStarts.length; i++) {
            if (windowStarts[i] != null && windowStarts[i].equals(targetWindow)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Calculate SMA ending at specific index.
     */
    private double calculateSMAAt(double[] data, int period, int endIdx) {
        int start = Math.max(0, endIdx - period + 1);
        double sum = 0;
        int count = 0;
        for (int i = start; i <= endIdx; i++) {
            sum += data[i];
            count++;
        }
        return count > 0 ? sum / count : 0;
    }

    /**
     * Calculate Standard Deviation ending at specific index.
     */
    private double calculateStdDevAt(double[] data, int period, double mean, int endIdx) {
        int start = Math.max(0, endIdx - period + 1);
        double sumSq = 0;
        int count = 0;
        for (int i = start; i <= endIdx; i++) {
            sumSq += Math.pow(data[i] - mean, 2);
            count++;
        }
        return count > 0 ? Math.sqrt(sumSq / count) : 0;
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
            .trendChanged(false)
            .barsInTrend(0)
            .build();
    }

    /**
     * Clear persisted state for a symbol (for testing or reset).
     */
    public void clearState(String symbol, String timeframe) {
        if (stateRepository != null) {
            try {
                stateRepository.deleteByScripCodeAndTimeframe(symbol, timeframe);
                log.info("[BBST-CALC] {} Cleared persisted state for timeframe {}", symbol, timeframe);
            } catch (Exception e) {
                log.warn("[BBST-CALC] {} Error clearing state: {}", symbol, e.getMessage());
            }
        }
    }

    /**
     * Volatility history tracking (separate from SuperTrend state).
     * This is kept for BB width averaging across calls.
     */
    private static class VolatilityHistory {
        double prevBbWidth = 0;
        List<Double> bbWidthHistory = new ArrayList<>();
    }
}
