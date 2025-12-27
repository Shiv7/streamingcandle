package com.kotsin.consumer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * TTLCache - Thread-safe cache with automatic expiry
 * 
 * Features:
 * - Configurable TTL per entry
 * - Automatic cleanup of expired entries
 * - Size limits with LRU eviction
 * - Cache miss logging
 * - Statistics tracking
 * 
 * @param <K> Key type
 * @param <V> Value type
 */
public class TTLCache<K, V> {

    private static final Logger LOGGER = LoggerFactory.getLogger(TTLCache.class);

    private final ConcurrentHashMap<K, CacheEntry<V>> cache = new ConcurrentHashMap<>();
    private final String cacheName;
    private final long defaultTtlMs;
    private final int maxSize;
    private final ScheduledExecutorService cleanupExecutor;

    // Statistics (AtomicLong for thread-safe increment operations)
    private final java.util.concurrent.atomic.AtomicLong hits = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong misses = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong evictions = new java.util.concurrent.atomic.AtomicLong(0);

    private static class CacheEntry<V> {
        final V value;
        final long expiryTime;
        volatile long lastAccessTime;

        CacheEntry(V value, long ttlMs) {
            this.value = value;
            this.expiryTime = System.currentTimeMillis() + ttlMs;
            this.lastAccessTime = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiryTime;
        }
    }

    /**
     * Create a TTL cache
     * 
     * @param cacheName Name for logging
     * @param defaultTtlMs Default TTL in milliseconds
     * @param maxSize Maximum cache size (0 = unlimited)
     * @param cleanupIntervalMs Cleanup interval in milliseconds
     */
    public TTLCache(String cacheName, long defaultTtlMs, int maxSize, long cleanupIntervalMs) {
        this.cacheName = cacheName;
        this.defaultTtlMs = defaultTtlMs;
        this.maxSize = maxSize;

        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TTLCache-" + cacheName + "-cleanup");
            t.setDaemon(true);
            return t;
        });

        // Schedule periodic cleanup
        cleanupExecutor.scheduleAtFixedRate(this::cleanup, 
                cleanupIntervalMs, cleanupIntervalMs, TimeUnit.MILLISECONDS);

        LOGGER.info("🗄️ Created TTLCache '{}' with TTL={}ms, maxSize={}", 
                cacheName, defaultTtlMs, maxSize);
    }

    /**
     * Put value with default TTL
     */
    public void put(K key, V value) {
        put(key, value, defaultTtlMs);
    }

    /**
     * Put value with custom TTL
     */
    public void put(K key, V value, long ttlMs) {
        if (key == null || value == null) return;

        // Check size limit
        if (maxSize > 0 && cache.size() >= maxSize) {
            evictOldest();
        }

        cache.put(key, new CacheEntry<>(value, ttlMs));
    }

    /**
     * Get value (null if not found or expired)
     */
    public V get(K key) {
        if (key == null) {
            misses.incrementAndGet();
            return null;
        }

        CacheEntry<V> entry = cache.get(key);
        if (entry == null) {
            misses.incrementAndGet();
            return null;
        }

        if (entry.isExpired()) {
            cache.remove(key);
            misses.incrementAndGet();
            evictions.incrementAndGet();
            return null;
        }

        entry.lastAccessTime = System.currentTimeMillis();
        hits.incrementAndGet();
        return entry.value;
    }

    /**
     * Get value or default if not found
     */
    public V getOrDefault(K key, V defaultValue) {
        V value = get(key);
        return value != null ? value : defaultValue;
    }

    /**
     * Get value with cache miss logging
     */
    public V getWithLogging(K key, String context) {
        V value = get(key);
        if (value == null && LOGGER.isDebugEnabled()) {
            LOGGER.debug("⚠️ Cache miss: {} key={} context={}", cacheName, key, context);
        }
        return value;
    }

    /**
     * Get value or compute if absent
     */
    public V getOrCompute(K key, java.util.function.Function<K, V> computeFunction) {
        V value = get(key);
        if (value != null) return value;

        value = computeFunction.apply(key);
        if (value != null) {
            put(key, value);
        }
        return value;
    }

    /**
     * Check if key exists and not expired
     */
    public boolean contains(K key) {
        return get(key) != null;
    }

    /**
     * Remove entry
     */
    public V remove(K key) {
        CacheEntry<V> entry = cache.remove(key);
        return entry != null ? entry.value : null;
    }

    /**
     * Clear all entries
     */
    public void clear() {
        int size = cache.size();
        cache.clear();
        LOGGER.info("🗑️ Cleared cache '{}', removed {} entries", cacheName, size);
    }

    /**
     * Get current size
     */
    public int size() {
        return cache.size();
    }

    /**
     * Cleanup expired entries
     */
    private void cleanup() {
        int removed = 0;
        Iterator<Map.Entry<K, CacheEntry<V>>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isExpired()) {
                it.remove();
                removed++;
                evictions.incrementAndGet();
            }
        }
        if (removed > 0) {
            LOGGER.debug("🧹 Cache '{}' cleanup: removed {} expired, size now {}",
                    cacheName, removed, cache.size());
        }
    }

    /**
     * Evict oldest entry (LRU)
     */
    private void evictOldest() {
        K oldestKey = null;
        long oldestTime = Long.MAX_VALUE;

        for (Map.Entry<K, CacheEntry<V>> entry : cache.entrySet()) {
            if (entry.getValue().lastAccessTime < oldestTime) {
                oldestTime = entry.getValue().lastAccessTime;
                oldestKey = entry.getKey();
            }
        }

        if (oldestKey != null) {
            cache.remove(oldestKey);
            evictions.incrementAndGet();
            LOGGER.debug("🗑️ Cache '{}' evicted oldest key: {}", cacheName, oldestKey);
        }
    }

    /**
     * Get statistics string
     */
    public String getStats() {
        long hitsVal = hits.get();
        long missesVal = misses.get();
        long evictionsVal = evictions.get();
        double hitRate = (hitsVal + missesVal) > 0 ? (double) hitsVal / (hitsVal + missesVal) * 100 : 0;
        return String.format("Cache '%s': size=%d, hits=%d, misses=%d, evictions=%d, hitRate=%.1f%%",
                cacheName, cache.size(), hitsVal, missesVal, evictionsVal, hitRate);
    }

    /**
     * Log statistics
     */
    public void logStats() {
        LOGGER.info("📊 {}", getStats());
    }

    /**
     * Iterate over all non-expired entries
     */
    public void forEach(BiConsumer<K, V> action) {
        cache.forEach((k, entry) -> {
            if (!entry.isExpired()) {
                action.accept(k, entry.value);
            }
        });
    }

    /**
     * Shutdown cleanup executor
     */
    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LOGGER.info("🛑 Cache '{}' shutdown complete", cacheName);
    }
}
