package com.kotsin.consumer.service;

import com.kotsin.consumer.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * RedisCacheService - Centralized Redis caching for v2 architecture.
 *
 * IMPORTANT: All keys use scripCode (NOT symbol) to avoid mixing data from
 * different instruments that share the same symbol name (e.g., SBICARD equity
 * vs SBICARD options).
 *
 * Key Structure:
 * - tick:{scripCode}:1m:latest       → Latest TickCandle (TTL: 5min)
 * - tick:{scripCode}:1m:history      → List of last 500 TickCandles (TTL: 24h)
 * - tick:{scripCode}:{tf}:latest     → Aggregated candle for timeframe (TTL: 5min)
 * - tick:{scripCode}:{tf}:history    → List of aggregated candles (TTL: 24h)
 * - ob:{scripCode}:latest            → Latest OrderbookMetrics (TTL: 5min)
 * - oi:{scripCode}:latest            → Latest OIMetrics (TTL: 5min)
 * - unified:{scripCode}:{tf}:latest  → Latest UnifiedCandle (TTL: 5min)
 */
@Service
@Slf4j
public class RedisCacheService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${v2.cache.tick.latest.ttl.minutes:5}")
    private int tickLatestTtlMinutes;

    @Value("${v2.cache.tick.history.ttl.hours:24}")
    private int tickHistoryTtlHours;

    @Value("${v2.cache.tick.history.max.size:500}")
    private int tickHistoryMaxSize;

    // ==================== TICK CANDLE CACHING ====================

    /**
     * Cache a tick candle (both latest and history).
     * Uses scripCode as key to avoid mixing data from different instruments.
     */
    public void cacheTickCandle(TickCandle candle) {
        if (candle == null || candle.getScripCode() == null) return;

        String scripCode = candle.getScripCode();

        try {
            // Cache as latest (keyed by scripCode)
            String latestKey = buildTickLatestKey(scripCode, Timeframe.M1);
            redisTemplate.opsForValue().set(latestKey, candle,
                Duration.ofMinutes(tickLatestTtlMinutes));

            // Add to history list (keyed by scripCode)
            String historyKey = buildTickHistoryKey(scripCode, Timeframe.M1);
            redisTemplate.opsForList().leftPush(historyKey, candle);
            redisTemplate.opsForList().trim(historyKey, 0, tickHistoryMaxSize - 1);
            redisTemplate.expire(historyKey, tickHistoryTtlHours, TimeUnit.HOURS);

        } catch (Exception e) {
            log.error("[REDIS-CACHE] Failed to cache tick candle for scripCode={}: {}",
                scripCode, e.getMessage());
        }
    }

    /**
     * Get latest tick candle by scripCode.
     */
    public TickCandle getLatestTickCandle(String scripCode) {
        String key = buildTickLatestKey(scripCode, Timeframe.M1);
        try {
            return (TickCandle) redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.error("[REDIS-CACHE] Failed to get latest tick for scripCode={}: {}",
                scripCode, e.getMessage());
            return null;
        }
    }

    /**
     * Get tick candle history by scripCode.
     */
    @SuppressWarnings("unchecked")
    public List<TickCandle> getTickHistory(String scripCode, int count) {
        String key = buildTickHistoryKey(scripCode, Timeframe.M1);
        try {
            List<Object> raw = redisTemplate.opsForList().range(key, 0, count - 1);
            if (raw == null) return new ArrayList<>();

            List<TickCandle> result = new ArrayList<>();
            for (Object obj : raw) {
                if (obj instanceof TickCandle) {
                    result.add((TickCandle) obj);
                }
            }
            return result;
        } catch (Exception e) {
            log.error("[REDIS-CACHE] Failed to get tick history for scripCode={}: {}",
                scripCode, e.getMessage());
            return new ArrayList<>();
        }
    }

    // ==================== AGGREGATED CANDLE CACHING ====================

    /**
     * Cache aggregated candle (5m, 15m, etc.) by scripCode.
     */
    public void cacheAggregatedCandle(String scripCode, Timeframe tf, UnifiedCandle candle) {
        if (candle == null || scripCode == null) return;

        try {
            // Cache as latest
            String latestKey = buildUnifiedLatestKey(scripCode, tf);
            redisTemplate.opsForValue().set(latestKey, candle,
                Duration.ofMinutes(tickLatestTtlMinutes));

            // Add to history
            String historyKey = buildUnifiedHistoryKey(scripCode, tf);
            redisTemplate.opsForList().leftPush(historyKey, candle);
            redisTemplate.opsForList().trim(historyKey, 0, tickHistoryMaxSize - 1);
            redisTemplate.expire(historyKey, tickHistoryTtlHours, TimeUnit.HOURS);

        } catch (Exception e) {
            log.error("[REDIS-CACHE] Failed to cache aggregated candle for scripCode={}:{}: {}",
                scripCode, tf, e.getMessage());
        }
    }

    /**
     * Get latest aggregated candle by scripCode.
     */
    public UnifiedCandle getLatestAggregatedCandle(String scripCode, Timeframe tf) {
        String key = buildUnifiedLatestKey(scripCode, tf);
        try {
            return (UnifiedCandle) redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.error("[REDIS-CACHE] Failed to get aggregated candle for scripCode={}:{}: {}",
                scripCode, tf, e.getMessage());
            return null;
        }
    }

    /**
     * Get aggregated candle history by scripCode.
     */
    @SuppressWarnings("unchecked")
    public List<UnifiedCandle> getAggregatedHistory(String scripCode, Timeframe tf, int count) {
        String key = buildUnifiedHistoryKey(scripCode, tf);
        try {
            List<Object> raw = redisTemplate.opsForList().range(key, 0, count - 1);
            if (raw == null) return new ArrayList<>();

            List<UnifiedCandle> result = new ArrayList<>();
            for (Object obj : raw) {
                if (obj instanceof UnifiedCandle) {
                    result.add((UnifiedCandle) obj);
                }
            }
            return result;
        } catch (Exception e) {
            log.error("[REDIS-CACHE] Failed to get aggregated history for scripCode={}:{}: {}",
                scripCode, tf, e.getMessage());
            return new ArrayList<>();
        }
    }

    // ==================== ORDERBOOK METRICS CACHING ====================

    /**
     * Cache orderbook metrics by scripCode.
     */
    public void cacheOrderbookMetrics(OrderbookMetrics metrics) {
        if (metrics == null || metrics.getScripCode() == null) return;

        try {
            String key = buildOrderbookKey(metrics.getScripCode());
            redisTemplate.opsForValue().set(key, metrics,
                Duration.ofMinutes(tickLatestTtlMinutes));
        } catch (Exception e) {
            log.error("[REDIS-CACHE] Failed to cache orderbook for scripCode={}: {}",
                metrics.getScripCode(), e.getMessage());
        }
    }

    /**
     * Get latest orderbook metrics by scripCode.
     */
    public OrderbookMetrics getLatestOrderbookMetrics(String scripCode) {
        String key = buildOrderbookKey(scripCode);
        try {
            return (OrderbookMetrics) redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.error("[REDIS-CACHE] Failed to get orderbook for scripCode={}: {}",
                scripCode, e.getMessage());
            return null;
        }
    }

    // ==================== OI METRICS CACHING ====================

    /**
     * Cache OI metrics by scripCode.
     */
    public void cacheOIMetrics(OIMetrics metrics) {
        if (metrics == null || metrics.getScripCode() == null) return;

        try {
            String key = buildOIKey(metrics.getScripCode());
            redisTemplate.opsForValue().set(key, metrics,
                Duration.ofMinutes(tickLatestTtlMinutes));
        } catch (Exception e) {
            log.error("[REDIS-CACHE] Failed to cache OI for scripCode={}: {}",
                metrics.getScripCode(), e.getMessage());
        }
    }

    /**
     * Get latest OI metrics by scripCode.
     */
    public OIMetrics getLatestOIMetrics(String scripCode) {
        String key = buildOIKey(scripCode);
        try {
            return (OIMetrics) redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.error("[REDIS-CACHE] Failed to get OI for scripCode={}: {}",
                scripCode, e.getMessage());
            return null;
        }
    }

    // ==================== UTILITY METHODS ====================

    /**
     * Get all cached scripCodes (from tick candle keys).
     */
    public Set<String> getCachedScripCodes() {
        try {
            return redisTemplate.keys("tick:*:1m:latest");
        } catch (Exception e) {
            log.error("[REDIS-CACHE] Failed to get cached scripCodes: {}", e.getMessage());
            return Set.of();
        }
    }

    /**
     * Clear cache for a scripCode.
     */
    public void clearScripCodeCache(String scripCode) {
        try {
            Set<String> keys = redisTemplate.keys("*:" + scripCode + ":*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.error("[REDIS-CACHE] Failed to clear cache for scripCode={}: {}",
                scripCode, e.getMessage());
        }
    }

    // ==================== KEY BUILDERS ====================

    private String buildTickLatestKey(String scripCode, Timeframe tf) {
        return String.format("tick:%s:%s:latest", scripCode, tf.getLabel());
    }

    private String buildTickHistoryKey(String scripCode, Timeframe tf) {
        return String.format("tick:%s:%s:history", scripCode, tf.getLabel());
    }

    private String buildUnifiedLatestKey(String scripCode, Timeframe tf) {
        return String.format("unified:%s:%s:latest", scripCode, tf.getLabel());
    }

    private String buildUnifiedHistoryKey(String scripCode, Timeframe tf) {
        return String.format("unified:%s:%s:history", scripCode, tf.getLabel());
    }

    private String buildOrderbookKey(String scripCode) {
        return String.format("ob:%s:latest", scripCode);
    }

    private String buildOIKey(String scripCode) {
        return String.format("oi:%s:latest", scripCode);
    }

    // ==================== PRICE CACHE FOR OI INTERPRETATION (v2.1) ====================

    private static final String PRICE_KEY_PREFIX = "price:";
    private static final String PREV_PRICE_KEY_PREFIX = "prevprice:";

    /**
     * Cache current price for a scripCode (for OI interpretation).
     * Also stores previous price before updating.
     */
    public void cachePrice(String scripCode, double price) {
        if (scripCode == null || price <= 0) return;

        try {
            String priceKey = PRICE_KEY_PREFIX + scripCode;
            String prevPriceKey = PREV_PRICE_KEY_PREFIX + scripCode;
            
            // Get current price to store as previous
            Object current = redisTemplate.opsForValue().get(priceKey);
            if (current != null) {
                redisTemplate.opsForValue().set(prevPriceKey, current, 
                    Duration.ofMinutes(tickLatestTtlMinutes));
            }
            
            // Store new price
            redisTemplate.opsForValue().set(priceKey, price,
                Duration.ofMinutes(tickLatestTtlMinutes));
        } catch (Exception e) {
            log.debug("[REDIS-CACHE] Failed to cache price for scripCode={}: {}", scripCode, e.getMessage());
        }
    }

    /**
     * Get last known price for a scripCode.
     */
    public Double getLastPrice(String scripCode) {
        if (scripCode == null) return null;

        try {
            String key = PRICE_KEY_PREFIX + scripCode;
            Object value = redisTemplate.opsForValue().get(key);
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
        } catch (Exception e) {
            log.debug("[REDIS-CACHE] Failed to get price for scripCode={}: {}", scripCode, e.getMessage());
        }
        return null;
    }

    /**
     * Get previous price for a scripCode.
     */
    public Double getPreviousPrice(String scripCode) {
        if (scripCode == null) return null;

        try {
            String key = PREV_PRICE_KEY_PREFIX + scripCode;
            Object value = redisTemplate.opsForValue().get(key);
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
        } catch (Exception e) {
            log.debug("[REDIS-CACHE] Failed to get previous price for scripCode={}: {}", scripCode, e.getMessage());
        }
        return null;
    }
}
