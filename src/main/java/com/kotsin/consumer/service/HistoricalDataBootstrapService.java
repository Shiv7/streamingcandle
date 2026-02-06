package com.kotsin.consumer.service;

import com.kotsin.consumer.client.FastAnalyticsClient;
import com.kotsin.consumer.metadata.model.ScripGroup;
import com.kotsin.consumer.model.HistoricalCandle;
import com.kotsin.consumer.model.TickCandle;
import com.kotsin.consumer.repository.ScripGroupRepository;
import com.kotsin.consumer.repository.TickCandleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.MongoBulkWriteException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * HistoricalDataBootstrapService - Loads historical candle data on startup.
 *
 * On startup:
 * 1. Reads instrument list directly from ScripGroup MongoDB collection (no REST API dependency)
 * 2. For each instrument, checks if MongoDB already has enough candles (70% threshold)
 * 3. Only fetches from FastAnalytics API if data is insufficient
 * 4. Caches recent candles in Redis for quick access
 *
 * On restart: Most instruments will skip API fetch (data already in MongoDB).
 * Typical restart bootstrap time: ~30-60s (MongoDB count checks only).
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
    private MongoTemplate mongoTemplate;

    @Autowired
    private RedisCacheService redisCacheService;

    @Autowired
    private ScripGroupRepository scripGroupRepository;

    @Value("${bootstrap.historical.enabled:true}")
    private boolean enabled;

    @Value("${bootstrap.historical.days:40}")
    private int historicalDays;

    @Value("${bootstrap.historical.threads:4}")
    private int numThreads;

    @Value("${bootstrap.historical.delay-seconds:10}")
    private int delaySeconds;

    @Value("${bootstrap.historical.startup.enabled:true}")
    private boolean startupBootstrapEnabled;

    private ExecutorService bootstrapExecutor;
    private final ConcurrentHashMap<String, BootstrapStatus> bootstrapStatus = new ConcurrentHashMap<>();
    private volatile boolean bootstrapComplete = false;

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

        log.info("{} Initialized: threads={}, historicalDays={}, delaySeconds={}",
            LOG_PREFIX, numThreads, historicalDays, delaySeconds);

        // Trigger startup bootstrap after delay
        if (startupBootstrapEnabled) {
            CompletableFuture.runAsync(() -> {
                try {
                    log.info("{} Waiting {}s before startup bootstrap...", LOG_PREFIX, delaySeconds);
                    Thread.sleep(delaySeconds * 1000L);
                    triggerStartupBootstrap();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("{} Startup bootstrap interrupted", LOG_PREFIX);
                }
            });
        }
    }

    /**
     * Trigger startup bootstrap by reading instruments directly from ScripGroup MongoDB collection.
     * No REST API dependency — reads from the same database that scripFinder populates.
     */
    private void triggerStartupBootstrap() {
        long startTimeMs = System.currentTimeMillis();
        log.info("{} Starting startup bootstrap from ScripGroup collection (direct MongoDB)", LOG_PREFIX);

        List<InstrumentInfo> instruments = new ArrayList<>();

        // Load all three trading types directly from MongoDB
        for (String tradingType : List.of("EQUITY", "COMMODITY", "CURRENCY")) {
            long queryStart = System.currentTimeMillis();
            try {
                List<ScripGroup> groups = scripGroupRepository.findByTradingType(tradingType);
                long queryMs = System.currentTimeMillis() - queryStart;

                if (groups == null || groups.isEmpty()) {
                    log.warn("{} No ScripGroup documents found for tradingType={} (query took {}ms)",
                        LOG_PREFIX, tradingType, queryMs);
                    continue;
                }

                int count = 0;
                for (ScripGroup group : groups) {
                    if (group.getEquityScripCode() == null || group.getEquity() == null) {
                        continue;
                    }

                    String scripCode = group.getEquityScripCode();
                    String symbol = group.getEquity().getName() != null ?
                        group.getEquity().getName() : scripCode;
                    String exch = group.getEquity().getExch() != null ?
                        group.getEquity().getExch() : "N";
                    String exchType = group.getEquity().getExchType() != null ?
                        group.getEquity().getExchType() : "C";

                    instruments.add(new InstrumentInfo(scripCode, symbol, exch, exchType));
                    count++;
                }

                log.info("{} Loaded {} instruments for tradingType={} from ScripGroup (query took {}ms)",
                    LOG_PREFIX, count, tradingType, queryMs);

            } catch (Exception e) {
                long queryMs = System.currentTimeMillis() - queryStart;
                log.error("{} Failed to load {} instruments from ScripGroup (after {}ms): {}",
                    LOG_PREFIX, tradingType, queryMs, e.getMessage());
            }
        }

        long loadMs = System.currentTimeMillis() - startTimeMs;
        log.info("{} Instrument discovery complete: {} total instruments across all markets (took {}ms)",
            LOG_PREFIX, instruments.size(), loadMs);

        if (!instruments.isEmpty()) {
            bootstrapSymbols(Set.copyOf(instruments));
        } else {
            log.error("{} No instruments found in ScripGroup collection - is scripFinder running?", LOG_PREFIX);
        }
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
            return CompletableFuture.completedFuture(true);
        }
        if (status == BootstrapStatus.IN_PROGRESS) {
            return CompletableFuture.completedFuture(false);
        }

        bootstrapStatus.put(scripCode, BootstrapStatus.IN_PROGRESS);

        return CompletableFuture.supplyAsync(() -> {
            try {
                return doBootstrap(scripCode, symbol, exch, exchType);
            } catch (Exception e) {
                log.error("{} Bootstrap failed for {} ({}): {}", LOG_PREFIX, symbol, scripCode, e.getMessage());
                bootstrapStatus.put(scripCode, BootstrapStatus.FAILED);
                return false;
            }
        }, bootstrapExecutor);
    }

    /**
     * Perform the actual bootstrap for a single instrument.
     *
     * Fast-path (restart): If a recent candle exists in MongoDB, mark SUCCESS immediately.
     * Uses findTop (O(1) index lookup) instead of count (O(n) full scan).
     *
     * Slow-path (first boot): If no recent candle, do full count check and API fetch.
     */
    private boolean doBootstrap(String scripCode, String symbol, String exch, String exchType) {
        long startMs = System.currentTimeMillis();

        // FAST-PATH: Check if recent candle exists (O(1) index lookup via scripCode_timestamp_idx)
        // If a candle exists within historicalDays, data was already bootstrapped — skip entirely.
        long checkStart = System.currentTimeMillis();
        java.util.Optional<TickCandle> recentCandle =
            tickCandleRepository.findTopByScripCodeOrderByTimestampDesc(scripCode);
        long checkMs = System.currentTimeMillis() - checkStart;

        if (recentCandle.isPresent()) {
            java.time.Instant candleTime = recentCandle.get().getTimestamp();
            java.time.Instant cutoff = java.time.Instant.now().minus(historicalDays, java.time.temporal.ChronoUnit.DAYS);

            if (candleTime != null && candleTime.isAfter(cutoff)) {
                bootstrapStatus.put(scripCode, BootstrapStatus.SUCCESS);
                log.debug("{} READY {} ({}) - recent candle exists ({}ms)",
                    LOG_PREFIX, symbol, scripCode, checkMs);
                return true;
            }
        }

        // SLOW-PATH: No recent candle found — do full count check
        String endDate = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        String startDate = LocalDate.now().minusDays(historicalDays).format(DateTimeFormatter.ISO_DATE);

        long countStart = System.currentTimeMillis();
        long existingCount = tickCandleRepository.countByScripCode(scripCode);
        long countMs = System.currentTimeMillis() - countStart;

        int minRequiredCandles = historicalDays * 375; // ~375 1m candles per trading day (6.25 hours)
        int acceptableThreshold = (int) (minRequiredCandles * 0.7); // 70% is acceptable

        if (existingCount >= acceptableThreshold) {
            bootstrapStatus.put(scripCode, BootstrapStatus.SUCCESS);
            log.debug("{} SKIP {} ({}) - MongoDB has {} candles >= threshold {} (check={}ms, count={}ms)",
                LOG_PREFIX, symbol, scripCode, existingCount, acceptableThreshold, checkMs, countMs);
            return true;
        }

        log.info("{} FETCH {} ({}) - MongoDB has {} candles, need {} - fetching {} to {} (check={}ms, count={}ms)",
            LOG_PREFIX, symbol, scripCode, existingCount, acceptableThreshold, startDate, endDate, checkMs, countMs);

        // Fetch historical data from FastAnalytics API
        long fetchStart = System.currentTimeMillis();
        List<HistoricalCandle> candles = fastAnalyticsClient.getHistoricalData(
            exch, exchType, scripCode, startDate, endDate, "1m");
        long fetchMs = System.currentTimeMillis() - fetchStart;

        if (candles == null || candles.isEmpty()) {
            log.warn("{} EMPTY {} ({}) - no historical data returned (API took {}ms)",
                LOG_PREFIX, symbol, scripCode, fetchMs);
            bootstrapStatus.put(scripCode, BootstrapStatus.FAILED);
            return false;
        }

        log.info("{} FETCHED {} ({}) - {} candles in {}ms", LOG_PREFIX, symbol, scripCode, candles.size(), fetchMs);

        // Convert to TickCandle entities
        List<TickCandle> tickCandles = candles.stream()
            .map(c -> c.toTickCandle(symbol, scripCode, exch, exchType))
            .collect(Collectors.toList());

        // Batch save to MongoDB with bulk unordered insert (skips duplicates automatically)
        long saveStart = System.currentTimeMillis();
        int saved = 0;
        int skipped = 0;
        int batchSize = 1000;

        for (int i = 0; i < tickCandles.size(); i += batchSize) {
            int end = Math.min(i + batchSize, tickCandles.size());
            List<TickCandle> batch = tickCandles.subList(i, end);

            try {
                BulkOperations bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, TickCandle.class);
                bulkOps.insert(batch);
                BulkWriteResult result = bulkOps.execute();
                saved += result.getInsertedCount();
            } catch (MongoBulkWriteException e) {
                saved += e.getWriteResult().getInsertedCount();
                skipped += e.getWriteErrors().size();
            } catch (Exception e) {
                log.warn("{} Bulk insert error for {} ({}): {}", LOG_PREFIX, symbol, scripCode, e.getMessage());
            }
        }
        long saveMs = System.currentTimeMillis() - saveStart;

        // Cache recent 100 candles in Redis for quick access
        long redisStart = System.currentTimeMillis();
        int redisCached = 0;
        try {
            List<TickCandle> recentCandles = tickCandles.stream()
                .sorted(Comparator.comparing(TickCandle::getTimestamp).reversed())
                .limit(100)
                .collect(Collectors.toList());

            for (TickCandle candle : recentCandles) {
                redisCacheService.cacheTickCandle(candle);
                redisCached++;
            }
        } catch (Exception e) {
            log.warn("{} Redis cache failed for {} ({}): {}", LOG_PREFIX, symbol, scripCode, e.getMessage());
        }
        long redisMs = System.currentTimeMillis() - redisStart;

        bootstrapStatus.put(scripCode, BootstrapStatus.SUCCESS);
        long totalMs = System.currentTimeMillis() - startMs;

        log.info("{} DONE {} ({}) - saved={}, skipped={}, redisCached={} | timing: total={}ms (count={}ms, fetch={}ms, save={}ms, redis={}ms)",
            LOG_PREFIX, symbol, scripCode, saved, skipped, redisCached, totalMs, countMs, fetchMs, saveMs, redisMs);

        return true;
    }

    /**
     * Bootstrap multiple symbols in parallel.
     * Tracks progress with periodic status logs and a final summary.
     */
    public void bootstrapSymbols(Set<InstrumentInfo> instruments) {
        if (!enabled || instruments == null || instruments.isEmpty()) {
            return;
        }

        long startMs = System.currentTimeMillis();
        int totalInstruments = instruments.size();
        log.info("{} Starting parallel bootstrap for {} instruments with {} threads",
            LOG_PREFIX, totalInstruments, numThreads);

        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        List<CompletableFuture<Boolean>> futures = instruments.stream()
            .map(inst -> bootstrapSymbol(inst.scripCode, inst.symbol, inst.exch, inst.exchType)
                .thenApply(result -> {
                    if (result) {
                        int s = success.incrementAndGet();
                        // Log progress every 50 instruments
                        if (s % 50 == 0) {
                            long elapsedMs = System.currentTimeMillis() - startMs;
                            log.info("{} Progress: {}/{} complete ({} success, {} failed) - elapsed {}ms",
                                LOG_PREFIX, s + failed.get(), totalInstruments, s, failed.get(), elapsedMs);
                        }
                    } else {
                        failed.incrementAndGet();
                    }
                    return result;
                }))
            .collect(Collectors.toList());

        // Wait for all to complete, then log final summary
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenRun(() -> {
                long totalMs = System.currentTimeMillis() - startMs;
                bootstrapComplete = true;
                log.info("{} ============================================================", LOG_PREFIX);
                log.info("{} BOOTSTRAP COMPLETE - READY TO TRADE", LOG_PREFIX);
                log.info("{} Total: {} instruments | Success: {} | Failed: {} | Duration: {}ms ({}s)",
                    LOG_PREFIX, totalInstruments, success.get(), failed.get(), totalMs,
                    String.format("%.1f", totalMs / 1000.0));
                log.info("{} ============================================================", LOG_PREFIX);
            });
    }

    /**
     * Check if bootstrap is fully complete (all instruments processed).
     */
    public boolean isBootstrapComplete() {
        return bootstrapComplete;
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
