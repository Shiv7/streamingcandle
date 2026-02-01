package com.kotsin.consumer.service;

import com.kotsin.consumer.model.*;
import com.kotsin.consumer.repository.TickCandleRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TimeframeAggregationService - Background service for pre-computing popular timeframes.
 *
 * This service runs in the background and pre-computes aggregated candles
 * for popular timeframes (5m, 15m, 30m, 1h) to ensure fast query response.
 *
 * Design:
 * - SCHEDULED: Runs every minute after each timeframe closes
 * - CACHED: Stores aggregated candles in Redis
 * - SELECTIVE: Only pre-computes for active symbols with recent data
 * - NON-BLOCKING: Uses separate thread pool for computation
 *
 * The CandleService can still compute on-demand if cache is missed.
 */
@Service
@Slf4j
public class TimeframeAggregationService {

    @Value("${v2.timeframe.aggregation.enabled:true}")
    private boolean enabled;

    @Value("${v2.timeframe.aggregation.threads:2}")
    private int numThreads;

    @Autowired
    private CandleService candleService;

    @Autowired
    private RedisCacheService redisCacheService;

    @Autowired
    private TickCandleRepository tickCandleRepository;

    // Popular timeframes to pre-compute
    private static final List<Timeframe> POPULAR_TIMEFRAMES = Arrays.asList(
        Timeframe.M5, Timeframe.M15, Timeframe.M30, Timeframe.H1
    );

    private ScheduledExecutorService scheduler;
    private ExecutorService computeExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Track last computed window for each timeframe
    private final Map<Timeframe, Instant> lastComputedWindow = new ConcurrentHashMap<>();

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("[TF-AGG-V2] Disabled by configuration");
            return;
        }

        log.info("[TF-AGG-V2] Starting with {} threads", numThreads);

        running.set(true);
        computeExecutor = Executors.newFixedThreadPool(numThreads);
        scheduler = Executors.newSingleThreadScheduledExecutor();

        // Run aggregation check every minute
        scheduler.scheduleAtFixedRate(this::checkAndCompute, 5, 60, TimeUnit.SECONDS);

        log.info("[TF-AGG-V2] Started successfully");
    }

    @PreDestroy
    public void stop() {
        log.info("[TF-AGG-V2] Stopping...");
        running.set(false);

        if (scheduler != null) scheduler.shutdown();
        if (computeExecutor != null) {
            computeExecutor.shutdown();
            try {
                computeExecutor.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        log.info("[TF-AGG-V2] Stopped");
    }

    /**
     * Check which timeframes need computation and trigger async computation.
     */
    private void checkAndCompute() {
        if (!running.get()) return;

        Instant now = Instant.now();

        for (Timeframe tf : POPULAR_TIMEFRAMES) {
            Instant windowEnd = tf.getWindowEnd(tf.alignToWindowStart(now));

            // Check if this window has already been computed
            Instant lastComputed = lastComputedWindow.get(tf);
            if (lastComputed != null && !windowEnd.isAfter(lastComputed)) {
                continue;  // Already computed
            }

            // Check if window has closed (with 2 second grace)
            if (now.isAfter(windowEnd.plusSeconds(2))) {
                log.debug("[TF-AGG-V2] Computing {} window ending at {}",
                    tf.getLabel(), formatTime(windowEnd));

                computeExecutor.submit(() -> computeTimeframe(tf, windowEnd));
            }
        }
    }

    /**
     * Compute aggregated candles for a timeframe.
     */
    private void computeTimeframe(Timeframe tf, Instant windowEnd) {
        try {
            Instant windowStart = windowEnd.minus(tf.getDuration());

            // Get active symbols (symbols with recent tick data)
            Set<String> activeSymbols = getActiveSymbols();

            if (activeSymbols.isEmpty()) {
                log.debug("[TF-AGG-V2] No active symbols for {} computation", tf.getLabel());
                lastComputedWindow.put(tf, windowEnd);
                return;
            }

            log.info("[TF-AGG-V2] Computing {} candles for {} symbols, window: {} - {}",
                tf.getLabel(), activeSymbols.size(),
                formatTime(windowStart), formatTime(windowEnd));

            int successCount = 0;
            int failCount = 0;

            for (String symbol : activeSymbols) {
                try {
                    UnifiedCandle candle = candleService.getCandle(symbol, windowEnd.minusSeconds(1), tf);

                    if (candle != null) {
                        // Cache in Redis
                        redisCacheService.cacheAggregatedCandle(symbol, tf, candle);
                        successCount++;
                    }
                } catch (Exception e) {
                    log.error("[TF-AGG-V2] Failed to compute {} for {}: {}",
                        tf.getLabel(), symbol, e.getMessage());
                    failCount++;
                }
            }

            log.info("[TF-AGG-V2] Completed {} computation: {} success, {} failed",
                tf.getLabel(), successCount, failCount);

            lastComputedWindow.put(tf, windowEnd);

        } catch (Exception e) {
            log.error("[TF-AGG-V2] Error computing {}: {}", tf.getLabel(), e.getMessage());
        }
    }

    /**
     * Get symbols that have recent tick data (active in last 5 minutes).
     */
    private Set<String> getActiveSymbols() {
        Set<String> symbols = new HashSet<>();

        // Get from Redis cache
        Set<String> cachedKeys = redisCacheService.getCachedSymbols();
        if (cachedKeys != null) {
            for (String key : cachedKeys) {
                // Key format: tick:SYMBOL:1m:latest
                String[] parts = key.split(":");
                if (parts.length >= 2) {
                    symbols.add(parts[1]);
                }
            }
        }

        // If no cached symbols, query MongoDB for recent data
        if (symbols.isEmpty()) {
            Instant cutoff = Instant.now().minus(java.time.Duration.ofMinutes(5));
            List<TickCandle> recentCandles = tickCandleRepository.findBySymbolOrderByTimestampDesc(
                "*", PageRequest.of(0, 1000));

            for (TickCandle candle : recentCandles) {
                if (candle.getTimestamp().isAfter(cutoff)) {
                    symbols.add(candle.getSymbol());
                }
            }
        }

        return symbols;
    }

    /**
     * Force computation for a specific symbol and timeframe.
     * Used for on-demand warming of cache.
     */
    public void forceCompute(String symbol, Timeframe tf) {
        try {
            Instant now = Instant.now();
            Instant windowEnd = tf.getWindowEnd(tf.alignToWindowStart(now));
            Instant windowStart = windowEnd.minus(tf.getDuration());

            log.info("[TF-AGG-V2] Force computing {} for symbol {}", tf.getLabel(), symbol);

            UnifiedCandle candle = candleService.getCandle(symbol, windowEnd.minusSeconds(1), tf);

            if (candle != null) {
                redisCacheService.cacheAggregatedCandle(symbol, tf, candle);
                log.info("[TF-AGG-V2] Force computed and cached {} for {}", tf.getLabel(), symbol);
            }
        } catch (Exception e) {
            log.error("[TF-AGG-V2] Force compute failed for {}:{}: {}",
                symbol, tf.getLabel(), e.getMessage());
        }
    }

    /**
     * Warm up cache for a list of symbols.
     * Used at startup or when new symbols are added.
     */
    public CompletableFuture<Void> warmupCache(List<String> symbols) {
        if (symbols.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        log.info("[TF-AGG-V2] Starting cache warmup for {} symbols", symbols.size());

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String symbol : symbols) {
            for (Timeframe tf : POPULAR_TIMEFRAMES) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(
                    () -> forceCompute(symbol, tf), computeExecutor);
                futures.add(future);
            }
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenRun(() -> log.info("[TF-AGG-V2] Cache warmup completed for {} symbols", symbols.size()));
    }

    /**
     * Get status of timeframe aggregation.
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("enabled", enabled);
        status.put("running", running.get());
        status.put("popularTimeframes", POPULAR_TIMEFRAMES);
        status.put("lastComputedWindows", new HashMap<>(lastComputedWindow));
        return status;
    }

    private String formatTime(Instant instant) {
        return ZonedDateTime.ofInstant(instant, IST).format(TIME_FMT);
    }
}
