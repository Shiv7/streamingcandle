package com.kotsin.consumer.service;

import com.kotsin.consumer.client.FastAnalyticsClient;
import com.kotsin.consumer.model.HistoricalCandle;
import com.kotsin.consumer.model.TickCandle;
import com.kotsin.consumer.repository.TickCandleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * HistoricalDataBootstrapService - Loads historical candle data on startup.
 *
 * Fetches 40 days of 1-minute candles from FastAnalytics API
 * and stores them in MongoDB for signal processing.
 */
@Service
@Slf4j
public class HistoricalDataBootstrapService {

    private static final String LOG_PREFIX = "[HIST-BOOTSTRAP]";

    @Autowired
    private FastAnalyticsClient fastAnalyticsClient;

    @Autowired
    private TickCandleRepository tickCandleRepository;

    @Autowired
    private RedisCacheService redisCacheService;

    @Value("${bootstrap.historical.enabled:true}")
    private boolean enabled;

    @Value("${bootstrap.historical.days:40}")
    private int historicalDays;

    @Value("${bootstrap.historical.threads:4}")
    private int numThreads;

    @Value("${bootstrap.historical.delay-seconds:10}")
    private int delaySeconds;

    private ExecutorService bootstrapExecutor;
    private final ConcurrentHashMap<String, BootstrapStatus> bootstrapStatus = new ConcurrentHashMap<>();

    public enum BootstrapStatus {
        NOT_STARTED, IN_PROGRESS, SUCCESS, FAILED
    }

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("{} Disabled by configuration", LOG_PREFIX);
            return;
        }

        bootstrapExecutor = Executors.newFixedThreadPool(numThreads, r -> {
            Thread t = new Thread(r);
            t.setName("hist-bootstrap-" + t.getId());
            t.setDaemon(true);
            return t;
        });

        log.info("{} Initialized with threads={}, historicalDays={}, delaySeconds={}",
            LOG_PREFIX, numThreads, historicalDays, delaySeconds);
    }

    @PreDestroy
    public void shutdown() {
        if (bootstrapExecutor != null) {
            bootstrapExecutor.shutdown();
            try {
                bootstrapExecutor.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Bootstrap historical data for a single symbol.
     * Called when a new symbol is detected.
     */
    public CompletableFuture<Boolean> bootstrapSymbol(String scripCode, String symbol,
            String exch, String exchType) {

        if (!enabled) {
            return CompletableFuture.completedFuture(false);
        }

        // Check if already bootstrapped
        BootstrapStatus status = bootstrapStatus.get(scripCode);
        if (status == BootstrapStatus.SUCCESS) {
            log.debug("{} Already bootstrapped: {}", LOG_PREFIX, scripCode);
            return CompletableFuture.completedFuture(true);
        }
        if (status == BootstrapStatus.IN_PROGRESS) {
            log.debug("{} Bootstrap in progress: {}", LOG_PREFIX, scripCode);
            return CompletableFuture.completedFuture(false);
        }

        bootstrapStatus.put(scripCode, BootstrapStatus.IN_PROGRESS);

        return CompletableFuture.supplyAsync(() -> {
            try {
                return doBootstrap(scripCode, symbol, exch, exchType);
            } catch (Exception e) {
                log.error("{} Bootstrap failed for {}: {}", LOG_PREFIX, scripCode, e.getMessage());
                bootstrapStatus.put(scripCode, BootstrapStatus.FAILED);
                return false;
            }
        }, bootstrapExecutor);
    }

    /**
     * Perform the actual bootstrap.
     */
    private boolean doBootstrap(String scripCode, String symbol, String exch, String exchType) {
        String endDate = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        String startDate = LocalDate.now().minusDays(historicalDays).format(DateTimeFormatter.ISO_DATE);

        log.info("{} Starting bootstrap for {} ({}) from {} to {}",
            LOG_PREFIX, symbol, scripCode, startDate, endDate);

        // Fetch historical data from FastAnalytics API
        List<HistoricalCandle> candles = fastAnalyticsClient.getHistoricalData(
            exch, exchType, scripCode, startDate, endDate, "1m");

        if (candles == null || candles.isEmpty()) {
            log.warn("{} No historical data for {} ({})", LOG_PREFIX, symbol, scripCode);
            bootstrapStatus.put(scripCode, BootstrapStatus.FAILED);
            return false;
        }

        log.info("{} Fetched {} candles for {} ({})", LOG_PREFIX, candles.size(), symbol, scripCode);

        // Convert to TickCandle entities
        List<TickCandle> tickCandles = candles.stream()
            .map(c -> c.toTickCandle(symbol, scripCode, exch, exchType))
            .collect(Collectors.toList());

        // Batch save to MongoDB
        try {
            int batchSize = 1000;
            int saved = 0;

            for (int i = 0; i < tickCandles.size(); i += batchSize) {
                int end = Math.min(i + batchSize, tickCandles.size());
                List<TickCandle> batch = tickCandles.subList(i, end);
                tickCandleRepository.saveAll(batch);
                saved += batch.size();

                if (saved % 5000 == 0) {
                    log.debug("{} Saved {}/{} candles for {}",
                        LOG_PREFIX, saved, tickCandles.size(), symbol);
                }
            }

            log.info("{} Saved {} candles to MongoDB for {} ({})",
                LOG_PREFIX, saved, symbol, scripCode);

        } catch (Exception e) {
            log.error("{} Failed to save to MongoDB for {}: {}",
                LOG_PREFIX, scripCode, e.getMessage());
            bootstrapStatus.put(scripCode, BootstrapStatus.FAILED);
            return false;
        }

        // Cache recent 100 candles in Redis for quick access
        try {
            List<TickCandle> recentCandles = tickCandles.stream()
                .sorted(Comparator.comparing(TickCandle::getTimestamp).reversed())
                .limit(100)
                .collect(Collectors.toList());

            for (TickCandle candle : recentCandles) {
                redisCacheService.cacheTickCandle(candle);
            }

            log.debug("{} Cached {} recent candles in Redis for {}",
                LOG_PREFIX, recentCandles.size(), symbol);

        } catch (Exception e) {
            log.warn("{} Failed to cache in Redis for {}: {}",
                LOG_PREFIX, scripCode, e.getMessage());
            // Don't fail bootstrap for Redis errors
        }

        bootstrapStatus.put(scripCode, BootstrapStatus.SUCCESS);
        log.info("{} Bootstrap complete for {} ({}) - {} candles",
            LOG_PREFIX, symbol, scripCode, tickCandles.size());

        return true;
    }

    /**
     * Bootstrap multiple symbols in parallel.
     */
    public void bootstrapSymbols(Set<InstrumentInfo> instruments) {
        if (!enabled || instruments == null || instruments.isEmpty()) {
            return;
        }

        log.info("{} Starting bootstrap for {} symbols", LOG_PREFIX, instruments.size());

        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        List<CompletableFuture<Boolean>> futures = instruments.stream()
            .map(inst -> bootstrapSymbol(inst.scripCode, inst.symbol, inst.exch, inst.exchType)
                .thenApply(result -> {
                    if (result) success.incrementAndGet();
                    else failed.incrementAndGet();
                    return result;
                }))
            .collect(Collectors.toList());

        // Wait for all to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenRun(() -> log.info("{} Bootstrap complete: {} success, {} failed",
                LOG_PREFIX, success.get(), failed.get()));
    }

    /**
     * Get bootstrap status for a symbol.
     */
    public BootstrapStatus getStatus(String scripCode) {
        return bootstrapStatus.getOrDefault(scripCode, BootstrapStatus.NOT_STARTED);
    }

    /**
     * Check if symbol is bootstrapped.
     */
    public boolean isBootstrapped(String scripCode) {
        return bootstrapStatus.get(scripCode) == BootstrapStatus.SUCCESS;
    }

    /**
     * Clear bootstrap status (for retry).
     */
    public void clearStatus(String scripCode) {
        bootstrapStatus.remove(scripCode);
    }

    /**
     * Get bootstrap statistics.
     */
    public BootstrapStats getStats() {
        int total = bootstrapStatus.size();
        int success = (int) bootstrapStatus.values().stream()
            .filter(s -> s == BootstrapStatus.SUCCESS).count();
        int failed = (int) bootstrapStatus.values().stream()
            .filter(s -> s == BootstrapStatus.FAILED).count();
        int inProgress = (int) bootstrapStatus.values().stream()
            .filter(s -> s == BootstrapStatus.IN_PROGRESS).count();

        return new BootstrapStats(total, success, failed, inProgress);
    }

    /**
     * Simple instrument info holder.
     */
    public record InstrumentInfo(String scripCode, String symbol, String exch, String exchType) {}

    /**
     * Bootstrap statistics.
     */
    public record BootstrapStats(int total, int success, int failed, int inProgress) {}
}
