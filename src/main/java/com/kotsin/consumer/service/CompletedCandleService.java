package com.kotsin.consumer.service;

import com.kotsin.consumer.event.CandleBoundaryEvent;
import com.kotsin.consumer.model.AggregatedCandle;
import com.kotsin.consumer.model.Timeframe;
import com.kotsin.consumer.model.UnifiedCandle;
import com.kotsin.consumer.repository.AggregatedCandleRepository;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * CompletedCandleService - Stores and retrieves COMPLETED HTF candles.
 *
 * IMPORTANT: This service ONLY stores COMPLETED candles (not in-progress).
 * This solves the "incomplete candle" problem where analysis was being done
 * on partially-formed HTF candles.
 *
 * Storage:
 * - In-memory cache with size limit per symbol:timeframe
 * - Redis for persistence (with TTL)
 *
 * Event-Driven:
 * - Listens for CandleBoundaryEvent
 * - When a timeframe boundary is crossed, stores the completed candle
 */
@Service
@Slf4j
public class CompletedCandleService {

    private static final String LOG_PREFIX = "[COMPLETED-CANDLE]";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private RedisCacheService redisCacheService;

    @Autowired
    @Lazy // Avoid circular dependency since CandleService also uses Redis
    private CandleService candleService;

    @Autowired
    private AggregatedCandleRepository aggregatedCandleRepository;

    // In-memory cache: key = scripCode:timeframe, value = list of completed candles
    // Using CopyOnWriteArrayList for thread safety
    private final Map<String, List<CompletedCandle>> candleCache = new ConcurrentHashMap<>();

    // Max candles to keep per symbol:timeframe
    private static final int MAX_CANDLES_PER_KEY = 100;

    // Redis key prefix
    private static final String REDIS_PREFIX = "htf:candle:";

    // Redis TTL by timeframe
    private static final Map<Timeframe, Duration> REDIS_TTL = Map.of(
        Timeframe.M5, Duration.ofHours(4),
        Timeframe.M15, Duration.ofHours(8),
        Timeframe.M30, Duration.ofHours(16),
        Timeframe.H1, Duration.ofDays(2),
        Timeframe.H4, Duration.ofDays(7),
        Timeframe.D1, Duration.ofDays(30)
    );

    /**
     * Listen for candle boundary events and store completed candles.
     */
    @EventListener
    public void onCandleBoundary(CandleBoundaryEvent event) {
        CandleBoundaryEvent.CandleBoundaryData data = event.getData();

        // Only store if candle is complete enough
        if (!data.isComplete()) {
            log.debug("{} {} {} Skipping incomplete candle ({}%)",
                LOG_PREFIX, data.getScripCode(), data.getTimeframe().getLabel(),
                String.format("%.0f", data.getCompletenessRatio() * 100));
            return;
        }

        CompletedCandle candle = CompletedCandle.builder()
            .scripCode(data.getScripCode())
            .symbol(data.getSymbol())
            .exchange(data.getExchange())
            .exchangeType(data.getExchangeType())
            .timeframe(data.getTimeframe())
            .windowStart(data.getWindowStart())
            .windowEnd(data.getWindowEnd())
            .open(data.getOpen())
            .high(data.getHigh())
            .low(data.getLow())
            .close(data.getClose())
            .volume(data.getVolume())
            .completeness(data.getCompletenessRatio())
            .storedAt(Instant.now())
            .build();

        storeCandle(candle);

        // Build full UnifiedCandle (with orderbook + OI) and cache in Redis + persist to MongoDB
        try {
            UnifiedCandle fullCandle = candleService.getCandle(
                data.getScripCode(), data.getWindowStart(), data.getTimeframe());
            if (fullCandle != null) {
                // Cache in Redis for fast signal generation
                redisCacheService.cacheAggregatedCandle(data.getScripCode(), data.getTimeframe(), fullCandle);

                // Persist to MongoDB for nightly review (7-day TTL via index)
                AggregatedCandle doc = AggregatedCandle.fromUnifiedCandle(
                    fullCandle, data.getScripCode(), data.getSymbol(),
                    data.getTimeframe(), data.getWindowStart(), data.getWindowEnd());
                aggregatedCandleRepository.save(doc);

                log.debug("{} {} {} Cached UnifiedCandle in Redis + MongoDB at {}",
                    LOG_PREFIX, data.getScripCode(), data.getTimeframe().getLabel(), data.getWindowEnd());
            }
        } catch (Exception e) {
            log.debug("{} {} Failed to cache UnifiedCandle: {}",
                LOG_PREFIX, data.getScripCode(), e.getMessage());
        }
    }

    /**
     * Store a completed candle.
     */
    public void storeCandle(CompletedCandle candle) {
        String cacheKey = candle.getScripCode() + ":" + candle.getTimeframe().getLabel();

        // Store in memory cache (thread-safe)
        List<CompletedCandle> candles = candleCache.computeIfAbsent(cacheKey,
            k -> new CopyOnWriteArrayList<>());

        // Check for duplicate
        boolean isDuplicate = candles.stream()
            .anyMatch(c -> c.getWindowEnd().equals(candle.getWindowEnd()));

        if (!isDuplicate) {
            candles.add(candle);

            // Trim if exceeds max
            if (candles.size() > MAX_CANDLES_PER_KEY) {
                // Sort by windowEnd and remove oldest
                candles.sort(Comparator.comparing(CompletedCandle::getWindowEnd));
                int toRemove = candles.size() - MAX_CANDLES_PER_KEY;
                for (int i = 0; i < toRemove; i++) {
                    candles.remove(0);
                }
            }

            log.debug("{} {} {} Stored completed candle at {}",
                LOG_PREFIX, candle.getScripCode(), candle.getTimeframe().getLabel(),
                candle.getWindowEnd());

            // Also store in Redis for persistence
            storeInRedis(candle);
        }
    }

    /**
     * Store candle in Redis.
     */
    private void storeInRedis(CompletedCandle candle) {
        try {
            String key = REDIS_PREFIX + candle.getTimeframe().getLabel() + ":"
                + candle.getScripCode() + ":" + candle.getWindowEnd().toEpochMilli();
            Duration ttl = REDIS_TTL.getOrDefault(candle.getTimeframe(), Duration.ofDays(1));
            redisTemplate.opsForValue().set(key, candle, ttl);
        } catch (Exception e) {
            log.debug("{} {} Redis store failed: {}", LOG_PREFIX, candle.getScripCode(), e.getMessage());
        }
    }

    /**
     * Get completed candles for a symbol and timeframe.
     * Returns candles ordered by windowEnd descending (most recent first).
     */
    public List<CompletedCandle> getCompletedCandles(String scripCode, Timeframe timeframe, int count) {
        String cacheKey = scripCode + ":" + timeframe.getLabel();
        List<CompletedCandle> candles = candleCache.get(cacheKey);

        if (candles == null || candles.isEmpty()) {
            return Collections.emptyList();
        }

        // Sort by windowEnd descending and limit
        return candles.stream()
            .sorted((a, b) -> b.getWindowEnd().compareTo(a.getWindowEnd()))
            .limit(count)
            .toList();
    }

    /**
     * Get the latest completed candle.
     */
    public Optional<CompletedCandle> getLatestCompletedCandle(String scripCode, Timeframe timeframe) {
        List<CompletedCandle> candles = getCompletedCandles(scripCode, timeframe, 1);
        return candles.isEmpty() ? Optional.empty() : Optional.of(candles.get(0));
    }

    /**
     * Get completed candles after a specific time.
     */
    public List<CompletedCandle> getCompletedCandlesAfter(String scripCode, Timeframe timeframe, Instant after) {
        String cacheKey = scripCode + ":" + timeframe.getLabel();
        List<CompletedCandle> candles = candleCache.get(cacheKey);

        if (candles == null || candles.isEmpty()) {
            return Collections.emptyList();
        }

        return candles.stream()
            .filter(c -> c.getWindowEnd().isAfter(after))
            .sorted((a, b) -> b.getWindowEnd().compareTo(a.getWindowEnd()))
            .toList();
    }

    /**
     * Check if we have enough completed candles for analysis.
     */
    public boolean hasEnoughCandles(String scripCode, Timeframe timeframe, int minRequired) {
        String cacheKey = scripCode + ":" + timeframe.getLabel();
        List<CompletedCandle> candles = candleCache.get(cacheKey);
        return candles != null && candles.size() >= minRequired;
    }

    /**
     * Get candle count for a symbol:timeframe.
     */
    public int getCandleCount(String scripCode, Timeframe timeframe) {
        String cacheKey = scripCode + ":" + timeframe.getLabel();
        List<CompletedCandle> candles = candleCache.get(cacheKey);
        return candles != null ? candles.size() : 0;
    }

    /**
     * Clear cache for a symbol.
     */
    public void clearCache(String scripCode) {
        candleCache.entrySet().removeIf(e -> e.getKey().startsWith(scripCode + ":"));
    }

    /**
     * Clear all cache.
     */
    public void clearAllCache() {
        candleCache.clear();
    }

    // ==================== DATA CLASS ====================

    @Data
    @Builder
    public static class CompletedCandle {
        private String scripCode;
        private String symbol;
        private String exchange;
        private String exchangeType;
        private Timeframe timeframe;
        private Instant windowStart;
        private Instant windowEnd;
        private double open;
        private double high;
        private double low;
        private double close;
        private long volume;
        private double completeness;
        private Instant storedAt;
    }
}
