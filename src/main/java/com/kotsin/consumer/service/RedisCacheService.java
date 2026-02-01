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
 * Key Structure:
 * - tick:{symbol}:1m:latest          → Latest TickCandle (TTL: 5min)
 * - tick:{symbol}:1m:history         → List of last 500 TickCandles (TTL: 24h)
 * - tick:{symbol}:{tf}:latest        → Aggregated candle for timeframe (TTL: 5min)
 * - tick:{symbol}:{tf}:history       → List of aggregated candles (TTL: 24h)
 * - ob:{symbol}:latest               → Latest OrderbookMetrics (TTL: 5min)
 * - oi:{symbol}:latest               → Latest OIMetrics (TTL: 5min)
 * - unified:{symbol}:{tf}:latest     → Latest UnifiedCandle (TTL: 5min)
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
     */
    public void cacheTickCandle(TickCandle candle) {
        if (candle == null || candle.getSymbol() == null) return;

        String symbol = candle.getSymbol();

        try {
            // Cache as latest
            String latestKey = buildTickLatestKey(symbol, Timeframe.M1);
            redisTemplate.opsForValue().set(latestKey, candle,
                Duration.ofMinutes(tickLatestTtlMinutes));

            // Add to history list
            String historyKey = buildTickHistoryKey(symbol, Timeframe.M1);
            redisTemplate.opsForList().leftPush(historyKey, candle);
            redisTemplate.opsForList().trim(historyKey, 0, tickHistoryMaxSize - 1);
            redisTemplate.expire(historyKey, tickHistoryTtlHours, TimeUnit.HOURS);

        } catch (Exception e) {
            log.error("[REDIS-CACHE] Failed to cache tick candle for {}: {}",
                symbol, e.getMessage());
        }
    }

    /**
     * Get latest tick candle.
     */
    public TickCandle getLatestTickCandle(String symbol) {
        String key = buildTickLatestKey(symbol, Timeframe.M1);
        try {
            return (TickCandle) redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.error("[REDIS-CACHE] Failed to get latest tick for {}: {}",
                symbol, e.getMessage());
            return null;
        }
    }

    /**
     * Get tick candle history.
     */
    @SuppressWarnings("unchecked")
    public List<TickCandle> getTickHistory(String symbol, int count) {
        String key = buildTickHistoryKey(symbol, Timeframe.M1);
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
            log.error("[REDIS-CACHE] Failed to get tick history for {}: {}",
                symbol, e.getMessage());
            return new ArrayList<>();
        }
    }

    // ==================== AGGREGATED CANDLE CACHING ====================

    /**
     * Cache aggregated candle (5m, 15m, etc.).
     */
    public void cacheAggregatedCandle(String symbol, Timeframe tf, UnifiedCandle candle) {
        if (candle == null || symbol == null) return;

        try {
            // Cache as latest
            String latestKey = buildUnifiedLatestKey(symbol, tf);
            redisTemplate.opsForValue().set(latestKey, candle,
                Duration.ofMinutes(tickLatestTtlMinutes));

            // Add to history
            String historyKey = buildUnifiedHistoryKey(symbol, tf);
            redisTemplate.opsForList().leftPush(historyKey, candle);
            redisTemplate.opsForList().trim(historyKey, 0, tickHistoryMaxSize - 1);
            redisTemplate.expire(historyKey, tickHistoryTtlHours, TimeUnit.HOURS);

        } catch (Exception e) {
            log.error("[REDIS-CACHE] Failed to cache aggregated candle for {}:{}: {}",
                symbol, tf, e.getMessage());
        }
    }

    /**
     * Get latest aggregated candle.
     */
    public UnifiedCandle getLatestAggregatedCandle(String symbol, Timeframe tf) {
        String key = buildUnifiedLatestKey(symbol, tf);
        try {
            return (UnifiedCandle) redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.error("[REDIS-CACHE] Failed to get aggregated candle for {}:{}: {}",
                symbol, tf, e.getMessage());
            return null;
        }
    }

    /**
     * Get aggregated candle history.
     */
    @SuppressWarnings("unchecked")
    public List<UnifiedCandle> getAggregatedHistory(String symbol, Timeframe tf, int count) {
        String key = buildUnifiedHistoryKey(symbol, tf);
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
            log.error("[REDIS-CACHE] Failed to get aggregated history for {}:{}: {}",
                symbol, tf, e.getMessage());
            return new ArrayList<>();
        }
    }

    // ==================== ORDERBOOK METRICS CACHING ====================

    /**
     * Cache orderbook metrics.
     */
    public void cacheOrderbookMetrics(OrderbookMetrics metrics) {
        if (metrics == null || metrics.getSymbol() == null) return;

        try {
            String key = buildOrderbookKey(metrics.getSymbol());
            redisTemplate.opsForValue().set(key, metrics,
                Duration.ofMinutes(tickLatestTtlMinutes));
        } catch (Exception e) {
            log.error("[REDIS-CACHE] Failed to cache orderbook for {}: {}",
                metrics.getSymbol(), e.getMessage());
        }
    }

    /**
     * Get latest orderbook metrics.
     */
    public OrderbookMetrics getLatestOrderbookMetrics(String symbol) {
        String key = buildOrderbookKey(symbol);
        try {
            return (OrderbookMetrics) redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.error("[REDIS-CACHE] Failed to get orderbook for {}: {}",
                symbol, e.getMessage());
            return null;
        }
    }

    // ==================== OI METRICS CACHING ====================

    /**
     * Cache OI metrics.
     */
    public void cacheOIMetrics(OIMetrics metrics) {
        if (metrics == null || metrics.getSymbol() == null) return;

        try {
            String key = buildOIKey(metrics.getSymbol());
            redisTemplate.opsForValue().set(key, metrics,
                Duration.ofMinutes(tickLatestTtlMinutes));
        } catch (Exception e) {
            log.error("[REDIS-CACHE] Failed to cache OI for {}: {}",
                metrics.getSymbol(), e.getMessage());
        }
    }

    /**
     * Get latest OI metrics.
     */
    public OIMetrics getLatestOIMetrics(String symbol) {
        String key = buildOIKey(symbol);
        try {
            return (OIMetrics) redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.error("[REDIS-CACHE] Failed to get OI for {}: {}",
                symbol, e.getMessage());
            return null;
        }
    }

    // ==================== UTILITY METHODS ====================

    /**
     * Get all cached symbols.
     */
    public Set<String> getCachedSymbols() {
        try {
            return redisTemplate.keys("tick:*:1m:latest");
        } catch (Exception e) {
            log.error("[REDIS-CACHE] Failed to get cached symbols: {}", e.getMessage());
            return Set.of();
        }
    }

    /**
     * Clear cache for a symbol.
     */
    public void clearSymbolCache(String symbol) {
        try {
            Set<String> keys = redisTemplate.keys("*:" + symbol + ":*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.error("[REDIS-CACHE] Failed to clear cache for {}: {}",
                symbol, e.getMessage());
        }
    }

    // ==================== KEY BUILDERS ====================

    private String buildTickLatestKey(String symbol, Timeframe tf) {
        return String.format("tick:%s:%s:latest", symbol, tf.getLabel());
    }

    private String buildTickHistoryKey(String symbol, Timeframe tf) {
        return String.format("tick:%s:%s:history", symbol, tf.getLabel());
    }

    private String buildUnifiedLatestKey(String symbol, Timeframe tf) {
        return String.format("unified:%s:%s:latest", symbol, tf.getLabel());
    }

    private String buildUnifiedHistoryKey(String symbol, Timeframe tf) {
        return String.format("unified:%s:%s:history", symbol, tf.getLabel());
    }

    private String buildOrderbookKey(String symbol) {
        return String.format("ob:%s:latest", symbol);
    }

    private String buildOIKey(String symbol) {
        return String.format("oi:%s:latest", symbol);
    }

    // ==================== PRICE CACHE FOR OI INTERPRETATION (v2.1) ====================

    private static final String PRICE_KEY_PREFIX = "price:";
    private static final String PREV_PRICE_KEY_PREFIX = "prevprice:";

    /**
     * Cache current price for a symbol (for OI interpretation).
     * Also stores previous price before updating.
     */
    public void cachePrice(String symbol, double price) {
        if (symbol == null || price <= 0) return;
        
        try {
            String priceKey = PRICE_KEY_PREFIX + symbol;
            String prevPriceKey = PREV_PRICE_KEY_PREFIX + symbol;
            
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
            log.debug("[REDIS-CACHE] Failed to cache price for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * Get last known price for a symbol.
     */
    public Double getLastPrice(String symbol) {
        if (symbol == null) return null;
        
        try {
            String key = PRICE_KEY_PREFIX + symbol;
            Object value = redisTemplate.opsForValue().get(key);
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
        } catch (Exception e) {
            log.debug("[REDIS-CACHE] Failed to get price for {}: {}", symbol, e.getMessage());
        }
        return null;
    }

    /**
     * Get previous price for a symbol.
     */
    public Double getPreviousPrice(String symbol) {
        if (symbol == null) return null;
        
        try {
            String key = PREV_PRICE_KEY_PREFIX + symbol;
            Object value = redisTemplate.opsForValue().get(key);
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
        } catch (Exception e) {
            log.debug("[REDIS-CACHE] Failed to get previous price for {}: {}", symbol, e.getMessage());
        }
        return null;
    }
}
