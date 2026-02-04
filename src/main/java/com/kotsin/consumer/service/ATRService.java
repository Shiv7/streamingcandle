package com.kotsin.consumer.service;

import com.kotsin.consumer.model.Timeframe;
import com.kotsin.consumer.model.UnifiedCandle;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ATRService - Calculates Average True Range for proper risk management.
 *
 * ATR is used for:
 * 1. Stop loss calculation (1.5-2x ATR from entry)
 * 2. Position sizing (risk per trade / ATR)
 * 3. Volatility assessment
 *
 * Caches ATR values with appropriate TTL per timeframe.
 */
@Service
@Slf4j
public class ATRService {

    private static final String LOG_PREFIX = "[ATR-SVC]";

    @Autowired
    private CandleService candleService;

    // Cache: key = scripCode:timeframe, value = ATRData
    private final Map<String, ATRData> atrCache = new ConcurrentHashMap<>();

    // Default ATR period
    private static final int DEFAULT_ATR_PERIOD = 14;

    // Cache TTL by timeframe (in seconds)
    private static final Map<Timeframe, Long> CACHE_TTL = Map.of(
        Timeframe.M5, 300L,        // 5 minutes
        Timeframe.M15, 900L,       // 15 minutes
        Timeframe.M30, 1800L,      // 30 minutes
        Timeframe.H1, 3600L,       // 1 hour
        Timeframe.H4, 14400L,      // 4 hours
        Timeframe.D1, 86400L       // 24 hours
    );

    /**
     * Get ATR for a symbol at a specific timeframe.
     * Uses cache if valid, otherwise calculates fresh.
     */
    public ATRData getATR(String scripCode, Timeframe timeframe) {
        return getATR(scripCode, timeframe, DEFAULT_ATR_PERIOD);
    }

    /**
     * Get ATR with custom period.
     */
    public ATRData getATR(String scripCode, Timeframe timeframe, int period) {
        String cacheKey = scripCode + ":" + timeframe.getLabel();

        // Check cache
        ATRData cached = atrCache.get(cacheKey);
        if (cached != null && !isCacheExpired(cached, timeframe)) {
            return cached;
        }

        // Calculate fresh ATR
        ATRData atr = calculateATR(scripCode, timeframe, period);
        if (atr != null) {
            atrCache.put(cacheKey, atr);
        }

        return atr;
    }

    /**
     * Calculate ATR from candle history.
     */
    public ATRData calculateATR(String scripCode, Timeframe timeframe, int period) {
        // Need period+1 candles for True Range calculation
        List<UnifiedCandle> candles = candleService.getCandleHistory(scripCode, timeframe, period + 1);

        if (candles == null || candles.size() < period + 1) {
            log.debug("{} {} Insufficient candles for ATR: have {}, need {}",
                LOG_PREFIX, scripCode, candles != null ? candles.size() : 0, period + 1);
            return null;
        }

        // Candles are ordered most recent first, reverse for calculation
        // Create mutable copy since the returned list may be immutable
        candles = new ArrayList<>(candles);
        java.util.Collections.reverse(candles);

        // Calculate True Range for each candle
        double[] trueRanges = new double[candles.size() - 1];
        for (int i = 1; i < candles.size(); i++) {
            UnifiedCandle current = candles.get(i);
            UnifiedCandle previous = candles.get(i - 1);
            trueRanges[i - 1] = calculateTrueRange(
                current.getHigh(), current.getLow(), previous.getClose());
        }

        // Calculate ATR as smoothed average of True Ranges
        // Using Wilder's smoothing: ATR = (ATR_prev * (n-1) + TR) / n
        double atr = 0;
        for (int i = 0; i < period; i++) {
            atr += trueRanges[i];
        }
        atr /= period;

        // Apply Wilder's smoothing for remaining TR values
        for (int i = period; i < trueRanges.length; i++) {
            atr = (atr * (period - 1) + trueRanges[i]) / period;
        }

        UnifiedCandle latest = candles.get(candles.size() - 1);
        double atrPercent = latest.getClose() > 0 ? (atr / latest.getClose()) * 100 : 0;

        ATRData result = ATRData.builder()
            .scripCode(scripCode)
            .timeframe(timeframe)
            .atr(atr)
            .atrPercent(atrPercent)
            .period(period)
            .lastPrice(latest.getClose())
            .calculatedAt(Instant.now())
            .build();

        log.debug("{} {} {} ATR({})={} ({:.2f}%)",
            LOG_PREFIX, scripCode, timeframe.getLabel(), period,
            String.format("%.2f", atr), atrPercent);

        return result;
    }

    /**
     * Calculate True Range.
     * TR = max(high - low, |high - prevClose|, |low - prevClose|)
     */
    private double calculateTrueRange(double high, double low, double prevClose) {
        double hl = high - low;
        double hpc = Math.abs(high - prevClose);
        double lpc = Math.abs(low - prevClose);
        return Math.max(hl, Math.max(hpc, lpc));
    }

    /**
     * Check if cached ATR is expired.
     */
    private boolean isCacheExpired(ATRData cached, Timeframe timeframe) {
        Long ttl = CACHE_TTL.getOrDefault(timeframe, 3600L);
        Duration age = Duration.between(cached.getCalculatedAt(), Instant.now());
        return age.getSeconds() > ttl;
    }

    /**
     * Calculate stop loss using ATR.
     *
     * @param scripCode Symbol
     * @param timeframe Timeframe for ATR
     * @param entryPrice Entry price
     * @param isBullish Direction
     * @param atrMultiplier Multiplier (typically 1.5-2.0)
     * @return Stop loss price
     */
    public double calculateATRStop(String scripCode, Timeframe timeframe,
                                    double entryPrice, boolean isBullish, double atrMultiplier) {
        ATRData atr = getATR(scripCode, timeframe);
        if (atr == null) {
            // Fallback: 1% stop
            return isBullish ? entryPrice * 0.99 : entryPrice * 1.01;
        }

        double stopDistance = atr.getAtr() * atrMultiplier;
        return isBullish ? entryPrice - stopDistance : entryPrice + stopDistance;
    }

    /**
     * Calculate target using ATR.
     *
     * @param scripCode Symbol
     * @param timeframe Timeframe for ATR
     * @param entryPrice Entry price
     * @param stopPrice Stop price
     * @param rMultiple R multiple for target (e.g., 2.0 for 2R)
     * @param isBullish Direction
     * @return Target price
     */
    public double calculateATRTarget(String scripCode, Timeframe timeframe,
                                      double entryPrice, double stopPrice,
                                      double rMultiple, boolean isBullish) {
        double risk = Math.abs(entryPrice - stopPrice);
        double reward = risk * rMultiple;
        return isBullish ? entryPrice + reward : entryPrice - reward;
    }

    /**
     * Get ATR-based risk/reward calculation.
     */
    public RiskRewardResult calculateRiskReward(String scripCode, Timeframe timeframe,
                                                 double entryPrice, boolean isBullish,
                                                 double atrMultiplierStop, double rMultipleTarget) {
        ATRData atr = getATR(scripCode, timeframe);
        if (atr == null) {
            return null;
        }

        double stopDistance = atr.getAtr() * atrMultiplierStop;
        double stopPrice = isBullish ? entryPrice - stopDistance : entryPrice + stopDistance;

        double risk = Math.abs(entryPrice - stopPrice);
        double targetDistance = risk * rMultipleTarget;
        double targetPrice = isBullish ? entryPrice + targetDistance : entryPrice - targetDistance;

        return RiskRewardResult.builder()
            .scripCode(scripCode)
            .timeframe(timeframe)
            .entryPrice(entryPrice)
            .stopPrice(stopPrice)
            .targetPrice(targetPrice)
            .risk(risk)
            .reward(targetDistance)
            .riskRewardRatio(rMultipleTarget)
            .atr(atr.getAtr())
            .atrPercent(atr.getAtrPercent())
            .atrMultiplierUsed(atrMultiplierStop)
            .isBullish(isBullish)
            .build();
    }

    /**
     * Clear cache for a symbol (call when new data arrives).
     */
    public void invalidateCache(String scripCode) {
        atrCache.entrySet().removeIf(e -> e.getKey().startsWith(scripCode + ":"));
    }

    /**
     * Clear all cache.
     */
    public void clearCache() {
        atrCache.clear();
    }

    // ==================== DATA CLASSES ====================

    @Data
    @Builder
    public static class ATRData {
        private String scripCode;
        private Timeframe timeframe;
        private double atr;
        private double atrPercent;
        private int period;
        private double lastPrice;
        private Instant calculatedAt;
    }

    @Data
    @Builder
    public static class RiskRewardResult {
        private String scripCode;
        private Timeframe timeframe;
        private double entryPrice;
        private double stopPrice;
        private double targetPrice;
        private double risk;
        private double reward;
        private double riskRewardRatio;
        private double atr;
        private double atrPercent;
        private double atrMultiplierUsed;
        private boolean isBullish;
    }
}
