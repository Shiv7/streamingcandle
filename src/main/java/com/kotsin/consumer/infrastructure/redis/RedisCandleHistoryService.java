package com.kotsin.consumer.infrastructure.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.model.UnifiedCandle;
import com.kotsin.consumer.util.NseWindowAligner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Async;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RedisCandleHistoryService - Single source of truth for historical candle data.
 *
 * ARCHITECTURE (v2 - with version tracking and retry):
 * =====================================================
 *
 * 1. VERSION-BASED BOOTSTRAP TRACKING
 *    - BOOTSTRAP_VERSION increments when schema changes (new timeframes added)
 *    - Instruments with old version are automatically re-bootstrapped
 *    - Redis key: bootstrap:version:{scripCode} -> version number
 *
 * 2. BOOTSTRAP STATES
 *    - NOT_STARTED: Never attempted
 *    - IN_PROGRESS: Currently bootstrapping (prevents duplicate calls)
 *    - SUCCESS: Completed with all timeframes
 *    - FAILED: Failed but can retry on next candle
 *
 * 3. RETRY MECHANISM
 *    - Failed bootstraps are NOT marked as "done"
 *    - On next candle arrival, bootstrap is retried
 *    - Max 3 retries with exponential backoff tracking
 *
 * 4. DEDICATED THREAD POOL
 *    - 4 threads for historical API calls (bootstrapExecutor)
 *    - Does NOT block Kafka consumer threads
 *
 * Redis Keys:
 * - candles:{timeframe}:{scripCode} -> Sorted set (score=timestamp, value=JSON)
 * - bootstrap:version:{scripCode} -> Bootstrap version number
 * - bootstrap:state:{scripCode} -> Bootstrap state (SUCCESS/FAILED)
 *
 * Used by:
 * - TechnicalIndicatorEnricher
 * - HtfCandleAggregator
 * - MasterArchProcessor
 * - BBSuperTrendDetector
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisCandleHistoryService {

    // ======================== VERSION TRACKING ========================
    /**
     * IMPORTANT: Increment this when adding new timeframes or changing aggregation logic.
     * Instruments with version < BOOTSTRAP_VERSION will be re-bootstrapped.
     *
     * Version history:
     * - v1: Initial (only 30m)
     * - v2: Added 1m, 5m, 15m, 1h
     * - v3: Added 2m, 3m, 2h, 4h, 1d (current)
     */
    private static final int BOOTSTRAP_VERSION = 3;

    /**
     * All timeframes that should be present after successful bootstrap.
     */
    private static final String[] REQUIRED_TIMEFRAMES = {
            "1m", "2m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "1d"
    };

    /**
     * Minimum candles per timeframe to consider bootstrap "complete".
     */
    private static final int MIN_CANDLES_PER_TIMEFRAME = 10;

    // ======================== DEPENDENCIES ========================

    private final StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // Increased timeouts for historical API reliability
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)   // Increased from 5s
            .readTimeout(30, TimeUnit.SECONDS)      // Increased from 10s
            .build();

    @Value("${history.base-url:http://localhost:8002}")
    private String historyBaseUrl;

    @Value("${candle.history.bootstrap.days:40}")
    private int bootstrapDays;

    @Value("${candle.history.max.candles:15000}")
    private int maxCandlesPerKey;

    @Value("${bootstrap.max.retries:3}")
    private int maxRetries;

    private ZSetOperations<String, String> zSetOps;

    // ======================== STATE TRACKING ========================

    /**
     * Bootstrap state enum for tracking progress.
     */
    public enum BootstrapState {
        NOT_STARTED,
        IN_PROGRESS,
        SUCCESS,
        FAILED
    }

    /**
     * Tracks bootstrap state for each scripCode.
     * Key: scripCode, Value: BootstrapState
     */
    private final Map<String, BootstrapState> bootstrapStates = new ConcurrentHashMap<>();

    /**
     * Tracks retry attempts for failed bootstraps.
     * Key: scripCode, Value: attempt count
     */
    private final Map<String, AtomicInteger> bootstrapRetryCount = new ConcurrentHashMap<>();

    /**
     * Tracks last bootstrap attempt time for rate limiting retries.
     * Key: scripCode, Value: timestamp millis
     */
    private final Map<String, Long> lastBootstrapAttempt = new ConcurrentHashMap<>();

    /**
     * Minimum time between retry attempts (5 seconds).
     */
    private static final long RETRY_COOLDOWN_MS = 5000;

    private static final String KEY_PREFIX = "candles:";
    private static final String VERSION_KEY_PREFIX = "bootstrap:version:";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ======================== INITIALIZATION ========================

    @PostConstruct
    public void init() {
        this.zSetOps = stringRedisTemplate.opsForZSet();
        log.info("[BOOTSTRAP] RedisCandleHistoryService initialized | version={} | " +
                        "bootstrapDays={} | maxCandles={} | maxRetries={} | " +
                        "requiredTimeframes={}",
                BOOTSTRAP_VERSION, bootstrapDays, maxCandlesPerKey, maxRetries,
                String.join(",", REQUIRED_TIMEFRAMES));
    }

    // ======================== PUBLIC API ========================

    /**
     * Get historical candles for a scripCode and timeframe.
     * Automatically triggers bootstrap if:
     * 1. Data is insufficient
     * 2. Bootstrap version is outdated
     * 3. Previous bootstrap failed (with retry cooldown)
     *
     * @param scripCode    Scrip code (e.g., "14154")
     * @param timeframe    Timeframe ("1m", "5m", "15m", "30m", "1h", "2h", "4h", "1d")
     * @param count        Number of candles to retrieve
     * @param exchange     Exchange code for bootstrap (e.g., "N" for NSE)
     * @param exchangeType Exchange type for bootstrap (e.g., "C" for Cash)
     * @return List of UnifiedCandle (most recent last)
     */
    public List<UnifiedCandle> getCandles(String scripCode, String timeframe, int count,
                                          String exchange, String exchangeType) {
        String key = buildKey(scripCode, timeframe);

        // Check if we need to bootstrap
        if (shouldTriggerBootstrap(scripCode, count)) {
            triggerAsyncBootstrap(scripCode, exchange, exchangeType);
        }

        // Fetch from Redis (reverse range to get most recent, then reverse to get chronological order)
        Set<String> jsonSet = zSetOps.reverseRange(key, 0, count - 1);
        if (jsonSet == null || jsonSet.isEmpty()) {
            log.debug("[BOOTSTRAP] No candles found for key={}", key);
            return Collections.emptyList();
        }

        List<UnifiedCandle> candles = new ArrayList<>();
        for (String json : jsonSet) {
            try {
                candles.add(mapper.readValue(json, UnifiedCandle.class));
            } catch (Exception e) {
                log.warn("[BOOTSTRAP] Failed to deserialize candle: {}", e.getMessage());
            }
        }

        // Reverse to chronological order (oldest first, newest last)
        Collections.reverse(candles);
        return candles;
    }

    /**
     * Determine if bootstrap should be triggered for a scripCode.
     */
    private boolean shouldTriggerBootstrap(String scripCode, int requiredCount) {
        BootstrapState state = bootstrapStates.getOrDefault(scripCode, BootstrapState.NOT_STARTED);

        // Already in progress - don't trigger again
        if (state == BootstrapState.IN_PROGRESS) {
            return false;
        }

        // Check version in Redis - re-bootstrap if outdated
        if (state == BootstrapState.SUCCESS) {
            Integer storedVersion = getStoredVersion(scripCode);
            if (storedVersion == null || storedVersion < BOOTSTRAP_VERSION) {
                log.info("[BOOTSTRAP] scripCode={} has outdated version ({} < {}), triggering re-bootstrap",
                        scripCode, storedVersion, BOOTSTRAP_VERSION);
                bootstrapStates.put(scripCode, BootstrapState.NOT_STARTED);
                state = BootstrapState.NOT_STARTED;
            } else {
                return false;  // Already successfully bootstrapped with current version
            }
        }

        // Failed state - check retry cooldown and max retries
        if (state == BootstrapState.FAILED) {
            AtomicInteger retries = bootstrapRetryCount.get(scripCode);
            if (retries != null && retries.get() >= maxRetries) {
                return false;  // Max retries exceeded
            }

            Long lastAttempt = lastBootstrapAttempt.get(scripCode);
            if (lastAttempt != null && (System.currentTimeMillis() - lastAttempt) < RETRY_COOLDOWN_MS) {
                return false;  // Cooldown not expired
            }

            log.info("[BOOTSTRAP] scripCode={} retrying bootstrap (attempt {}/{})",
                    scripCode, retries != null ? retries.get() + 1 : 1, maxRetries);
        }

        // Check if we have insufficient data
        String oneMinKey = buildKey(scripCode, "1m");
        Long size = zSetOps.zCard(oneMinKey);
        return size == null || size < requiredCount;
    }

    /**
     * Get stored bootstrap version from Redis.
     */
    private Integer getStoredVersion(String scripCode) {
        String versionKey = VERSION_KEY_PREFIX + scripCode;
        String value = stringRedisTemplate.opsForValue().get(versionKey);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Store bootstrap version in Redis.
     */
    private void storeVersion(String scripCode, int version) {
        String versionKey = VERSION_KEY_PREFIX + scripCode;
        stringRedisTemplate.opsForValue().set(versionKey, String.valueOf(version));
    }

    /**
     * Store a new candle in Redis (called from real-time stream processors).
     *
     * @param candle UnifiedCandle to store
     */
    public void storeCandle(UnifiedCandle candle) {
        if (candle == null || candle.getScripCode() == null || candle.getTimeframe() == null) {
            return;
        }

        String key = buildKey(candle.getScripCode(), candle.getTimeframe());

        try {
            String json = mapper.writeValueAsString(candle);
            double score = candle.getWindowStartMillis();

            zSetOps.add(key, json, score);

            // Trim to max size
            Long size = zSetOps.zCard(key);
            if (size != null && size > maxCandlesPerKey) {
                zSetOps.removeRange(key, 0, size - maxCandlesPerKey - 1);
            }

            log.trace("[BOOTSTRAP] Stored candle scripCode={} timeframe={} timestamp={}",
                    candle.getScripCode(), candle.getTimeframe(), candle.getWindowStartMillis());
        } catch (Exception e) {
            log.warn("[BOOTSTRAP] Failed to store candle for scripCode={}: {}",
                    candle.getScripCode(), e.getMessage());
        }
    }

    /**
     * Check if a scripCode has sufficient history for calculations.
     */
    public boolean hasSufficientHistory(String scripCode, String timeframe, int requiredCount) {
        String key = buildKey(scripCode, timeframe);
        Long size = zSetOps.zCard(key);
        return size != null && size >= requiredCount;
    }

    /**
     * Get the count of candles in Redis for a scripCode/timeframe.
     */
    public long getCandleCount(String scripCode, String timeframe) {
        String key = buildKey(scripCode, timeframe);
        Long size = zSetOps.zCard(key);
        return size != null ? size : 0;
    }

    /**
     * Check if a scripCode has complete data (all required timeframes with minimum candles).
     *
     * @param scripCode Scrip code to check
     * @return true if all timeframes have at least MIN_CANDLES_PER_TIMEFRAME candles
     */
    public boolean hasCompleteData(String scripCode) {
        for (String tf : REQUIRED_TIMEFRAMES) {
            long count = getCandleCount(scripCode, tf);
            if (count < MIN_CANDLES_PER_TIMEFRAME) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get detailed data completeness report for a scripCode.
     */
    public Map<String, Long> getDataCompleteness(String scripCode) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String tf : REQUIRED_TIMEFRAMES) {
            result.put(tf, getCandleCount(scripCode, tf));
        }
        return result;
    }

    // ======================== BOOTSTRAP MANAGEMENT API ========================

    /**
     * Clear bootstrap state for a specific scripCode.
     * This allows re-bootstrapping when schema changes or data is incomplete.
     *
     * @param scripCode Scrip code to clear
     */
    public void clearBootstrapState(String scripCode) {
        bootstrapStates.remove(scripCode);
        bootstrapRetryCount.remove(scripCode);
        lastBootstrapAttempt.remove(scripCode);

        // Also clear version in Redis
        String versionKey = VERSION_KEY_PREFIX + scripCode;
        stringRedisTemplate.delete(versionKey);

        log.info("[BOOTSTRAP] Cleared bootstrap state for scripCode={} - will re-bootstrap on next access",
                scripCode);
    }

    /**
     * Clear ALL bootstrap state.
     * Use when adding new timeframes or changing aggregation logic.
     */
    public void clearAllBootstrapState() {
        int count = bootstrapStates.size();
        bootstrapStates.clear();
        bootstrapRetryCount.clear();
        lastBootstrapAttempt.clear();

        // Clear all version keys in Redis
        Set<String> versionKeys = stringRedisTemplate.keys(VERSION_KEY_PREFIX + "*");
        if (versionKeys != null && !versionKeys.isEmpty()) {
            stringRedisTemplate.delete(versionKeys);
        }

        log.info("[BOOTSTRAP] Cleared ALL bootstrap state | count={}", count);
    }

    /**
     * Clear bootstrap state for instruments with incomplete data.
     *
     * @return Number of instruments cleared
     */
    public int clearIncompleteBootstrapStates() {
        int cleared = 0;
        List<String> toClear = new ArrayList<>();

        for (String scripCode : bootstrapStates.keySet()) {
            if (bootstrapStates.get(scripCode) == BootstrapState.SUCCESS && !hasCompleteData(scripCode)) {
                toClear.add(scripCode);
            }
        }

        for (String scripCode : toClear) {
            clearBootstrapState(scripCode);
            cleared++;
        }

        log.info("[BOOTSTRAP] Cleared {} incomplete bootstrap states", cleared);
        return cleared;
    }

    /**
     * Get bootstrap statistics.
     */
    public Map<String, Object> getBootstrapStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("bootstrapVersion", BOOTSTRAP_VERSION);
        stats.put("totalTracked", bootstrapStates.size());

        int notStarted = 0, inProgress = 0, success = 0, failed = 0;
        for (BootstrapState state : bootstrapStates.values()) {
            switch (state) {
                case NOT_STARTED -> notStarted++;
                case IN_PROGRESS -> inProgress++;
                case SUCCESS -> success++;
                case FAILED -> failed++;
            }
        }

        stats.put("notStarted", notStarted);
        stats.put("inProgress", inProgress);
        stats.put("success", success);
        stats.put("failed", failed);
        stats.put("requiredTimeframes", REQUIRED_TIMEFRAMES);
        stats.put("minCandlesPerTimeframe", MIN_CANDLES_PER_TIMEFRAME);

        return stats;
    }

    /**
     * Get list of instruments with incomplete data.
     *
     * @param limit Maximum number to return
     * @return List of scripCodes with incomplete data
     */
    public List<String> getIncompleteInstruments(int limit) {
        List<String> incomplete = new ArrayList<>();

        for (String scripCode : bootstrapStates.keySet()) {
            if (bootstrapStates.get(scripCode) == BootstrapState.SUCCESS && !hasCompleteData(scripCode)) {
                incomplete.add(scripCode);
                if (incomplete.size() >= limit) {
                    break;
                }
            }
        }

        return incomplete;
    }

    /**
     * Get bootstrap state for a specific scripCode.
     */
    public BootstrapState getBootstrapState(String scripCode) {
        return bootstrapStates.getOrDefault(scripCode, BootstrapState.NOT_STARTED);
    }

    // ======================== BOOTSTRAP LOGIC ========================

    /**
     * Trigger async bootstrap - does NOT block the calling thread.
     * Uses dedicated bootstrap thread pool (4 threads).
     */
    private void triggerAsyncBootstrap(String scripCode, String exchange, String exchangeType) {
        BootstrapState currentState = bootstrapStates.get(scripCode);
        if (currentState == BootstrapState.IN_PROGRESS) {
            return;  // Already in progress
        }

        bootstrapStates.put(scripCode, BootstrapState.IN_PROGRESS);
        lastBootstrapAttempt.put(scripCode, System.currentTimeMillis());

        log.info("[BOOTSTRAP] Triggering async bootstrap for scripCode={} exchange={}:{}",
                scripCode, exchange, exchangeType);

        bootstrapFromApiAsync(scripCode, exchange, exchangeType);
    }

    /**
     * ASYNC Bootstrap candles from 5paisa API for a scripCode.
     * Runs on dedicated thread pool (bootstrapExecutor - 4 threads).
     * Fetches last N days of 1m candles and aggregates to all timeframes.
     */
    @Async("bootstrapExecutor")
    public void bootstrapFromApiAsync(String scripCode, String exchange, String exchangeType) {
        try {
            bootstrapFromApi(scripCode, exchange, exchangeType);
        } catch (Exception e) {
            log.error("[BOOTSTRAP] Async bootstrap failed for scripCode={}: {}",
                    scripCode, e.getMessage(), e);
            bootstrapStates.put(scripCode, BootstrapState.FAILED);
            bootstrapRetryCount.computeIfAbsent(scripCode, k -> new AtomicInteger(0)).incrementAndGet();
        }
    }

    /**
     * Synchronous bootstrap - used directly when async is not needed.
     * DO NOT call this from Kafka consumer threads.
     */
    public void bootstrapFromApi(String scripCode, String exchange, String exchangeType) {
        LocalDate endDate = LocalDate.now(IST);
        LocalDate startDate = endDate.minusDays(bootstrapDays);

        log.info("[BOOTSTRAP] Starting bootstrap for scripCode={} | dateRange={} to {} | exchange={}:{}",
                scripCode, startDate, endDate, exchange, exchangeType);

        long startTime = System.currentTimeMillis();

        try {
            List<ApiCandle> apiCandles = fetchFromApi(scripCode, startDate, endDate, exchange, exchangeType);

            if (apiCandles.isEmpty()) {
                log.warn("[BOOTSTRAP] No candles returned from API for scripCode={}", scripCode);
                bootstrapStates.put(scripCode, BootstrapState.FAILED);
                bootstrapRetryCount.computeIfAbsent(scripCode, k -> new AtomicInteger(0)).incrementAndGet();
                return;
            }

            // Store 1m candles
            int stored1m = 0;
            for (ApiCandle ac : apiCandles) {
                UnifiedCandle candle = convertApiCandle(ac, scripCode, exchange, exchangeType, "1m");
                storeCandle(candle);
                stored1m++;
            }

            // Aggregate to all timeframes
            Map<String, Integer> aggregatedCounts = new LinkedHashMap<>();

            // 2m
            List<UnifiedCandle> candles2m = aggregateToTimeframe(apiCandles, scripCode, exchange, exchangeType, "2m", 2);
            for (UnifiedCandle c : candles2m) storeCandle(c);
            aggregatedCounts.put("2m", candles2m.size());

            // 3m
            List<UnifiedCandle> candles3m = aggregateToTimeframe(apiCandles, scripCode, exchange, exchangeType, "3m", 3);
            for (UnifiedCandle c : candles3m) storeCandle(c);
            aggregatedCounts.put("3m", candles3m.size());

            // 5m
            List<UnifiedCandle> candles5m = aggregateToTimeframe(apiCandles, scripCode, exchange, exchangeType, "5m", 5);
            for (UnifiedCandle c : candles5m) storeCandle(c);
            aggregatedCounts.put("5m", candles5m.size());

            // 15m
            List<UnifiedCandle> candles15m = aggregateToTimeframe(apiCandles, scripCode, exchange, exchangeType, "15m", 15);
            for (UnifiedCandle c : candles15m) storeCandle(c);
            aggregatedCounts.put("15m", candles15m.size());

            // 30m (NSE-aligned)
            List<UnifiedCandle> candles30m = aggregate1mTo30m(apiCandles, scripCode, exchange, exchangeType);
            for (UnifiedCandle c : candles30m) storeCandle(c);
            aggregatedCounts.put("30m", candles30m.size());

            // 1h
            List<UnifiedCandle> candles1h = aggregateToTimeframe(apiCandles, scripCode, exchange, exchangeType, "1h", 60);
            for (UnifiedCandle c : candles1h) storeCandle(c);
            aggregatedCounts.put("1h", candles1h.size());

            // 2h
            List<UnifiedCandle> candles2h = aggregateToTimeframe(apiCandles, scripCode, exchange, exchangeType, "2h", 120);
            for (UnifiedCandle c : candles2h) storeCandle(c);
            aggregatedCounts.put("2h", candles2h.size());

            // 4h
            List<UnifiedCandle> candles4h = aggregateToTimeframe(apiCandles, scripCode, exchange, exchangeType, "4h", 240);
            for (UnifiedCandle c : candles4h) storeCandle(c);
            aggregatedCounts.put("4h", candles4h.size());

            // 1d (daily)
            List<UnifiedCandle> candles1d = aggregateToDailyCandles(apiCandles, scripCode, exchange, exchangeType);
            for (UnifiedCandle c : candles1d) storeCandle(c);
            aggregatedCounts.put("1d", candles1d.size());

            // Mark as success and store version
            bootstrapStates.put(scripCode, BootstrapState.SUCCESS);
            storeVersion(scripCode, BOOTSTRAP_VERSION);
            bootstrapRetryCount.remove(scripCode);  // Clear retry count on success

            long elapsed = System.currentTimeMillis() - startTime;

            log.info("[BOOTSTRAP] SUCCESS scripCode={} | 1m={} | {} | version={} | elapsed={}ms",
                    scripCode,
                    stored1m,
                    aggregatedCounts.entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .reduce((a, b) -> a + " " + b).orElse(""),
                    BOOTSTRAP_VERSION,
                    elapsed);

        } catch (Exception e) {
            log.error("[BOOTSTRAP] FAILED scripCode={}: {}", scripCode, e.getMessage(), e);
            bootstrapStates.put(scripCode, BootstrapState.FAILED);
            bootstrapRetryCount.computeIfAbsent(scripCode, k -> new AtomicInteger(0)).incrementAndGet();
        }
    }

    /**
     * Fetch candles from 5paisa historical API with retry logic.
     */
    private List<ApiCandle> fetchFromApi(String scripCode, LocalDate startDate, LocalDate endDate,
                                         String exchange, String exchangeType) {
        int attempts = 0;
        Exception lastException = null;

        while (attempts < 3) {  // 3 retries for HTTP calls
            attempts++;
            try {
                HttpUrl url = HttpUrl.parse(historyBaseUrl)
                        .newBuilder()
                        .addPathSegments("getHisDataFromFivePaisa")
                        .addQueryParameter("scrip_code", scripCode)
                        .addQueryParameter("start_date", startDate.toString())
                        .addQueryParameter("end_date", endDate.plusDays(1).toString())
                        .addQueryParameter("exch", exchange.toLowerCase())
                        .addQueryParameter("exch_type", exchangeType.toLowerCase())
                        .addQueryParameter("interval", "1m")
                        .build();

                log.debug("[BOOTSTRAP] Fetching from API: {} (attempt {}/3)", url, attempts);
                Request req = new Request.Builder().url(url).get().build();

                try (Response resp = httpClient.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        log.warn("[BOOTSTRAP] API returned non-200 for scripCode={}: status={} (attempt {}/3)",
                                scripCode, resp.code(), attempts);
                        lastException = new RuntimeException("HTTP " + resp.code());
                        continue;
                    }

                    List<ApiCandle> candles = mapper.readValue(resp.body().byteStream(),
                            new TypeReference<List<ApiCandle>>() {
                            });
                    log.debug("[BOOTSTRAP] Fetched {} candles for scripCode={} from API",
                            candles.size(), scripCode);
                    return candles;
                }
            } catch (Exception e) {
                log.warn("[BOOTSTRAP] API fetch failed for scripCode={} (attempt {}/3): {}",
                        scripCode, attempts, e.getMessage());
                lastException = e;

                // Wait before retry (exponential backoff: 1s, 2s, 4s)
                if (attempts < 3) {
                    try {
                        Thread.sleep(1000L * (1 << (attempts - 1)));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        log.error("[BOOTSTRAP] API fetch exhausted all retries for scripCode={}: {}",
                scripCode, lastException != null ? lastException.getMessage() : "unknown");
        return Collections.emptyList();
    }

    /**
     * Aggregate 1m candles to 30m candles with NSE-aligned windows.
     */
    private List<UnifiedCandle> aggregate1mTo30m(List<ApiCandle> candles1m, String scripCode,
                                                 String exchange, String exchangeType) {
        List<UnifiedCandle> candles30m = new ArrayList<>();

        candles1m.sort(Comparator.comparing(c -> c.datetime));

        long periodMs = 30 * 60 * 1000L;
        Map<Long, List<ApiCandle>> windows = new LinkedHashMap<>();
        for (ApiCandle c : candles1m) {
            long windowStart = NseWindowAligner.getAlignedWindowStart(c.getTimestampMillis(), periodMs);
            windows.computeIfAbsent(windowStart, k -> new ArrayList<>()).add(c);
        }

        for (Map.Entry<Long, List<ApiCandle>> entry : windows.entrySet()) {
            long windowStart = entry.getKey();
            List<ApiCandle> windowCandles = entry.getValue();

            if (windowCandles.isEmpty()) continue;

            windowCandles.sort(Comparator.comparing(ApiCandle::getTimestampMillis));

            double open = windowCandles.get(0).open;
            double close = windowCandles.get(windowCandles.size() - 1).close;
            double high = windowCandles.stream().mapToDouble(c -> c.high).max().orElse(0);
            double low = windowCandles.stream().mapToDouble(c -> c.low).min().orElse(0);
            long volume = windowCandles.stream().mapToLong(c -> c.volume).sum();

            UnifiedCandle candle30m = UnifiedCandle.builder()
                    .scripCode(scripCode)
                    .exchange(exchange)
                    .exchangeType(exchangeType)
                    .timeframe("30m")
                    .windowStartMillis(windowStart)
                    .windowEndMillis(windowStart + periodMs)
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .volume(volume)
                    .build();

            candle30m.updateHumanReadableTimestamps();
            candles30m.add(candle30m);
        }

        return candles30m;
    }

    /**
     * Generic method to aggregate 1m candles to any timeframe with NSE-aligned windows.
     */
    private List<UnifiedCandle> aggregateToTimeframe(List<ApiCandle> candles1m, String scripCode,
                                                     String exchange, String exchangeType,
                                                     String timeframe, int periodMinutes) {
        List<UnifiedCandle> result = new ArrayList<>();

        candles1m.sort(Comparator.comparing(c -> c.datetime));

        long periodMs = periodMinutes * 60 * 1000L;
        Map<Long, List<ApiCandle>> windows = new LinkedHashMap<>();
        for (ApiCandle c : candles1m) {
            long windowStart = NseWindowAligner.getAlignedWindowStart(c.getTimestampMillis(), periodMs);
            windows.computeIfAbsent(windowStart, k -> new ArrayList<>()).add(c);
        }

        for (Map.Entry<Long, List<ApiCandle>> entry : windows.entrySet()) {
            long windowStart = entry.getKey();
            List<ApiCandle> windowCandles = entry.getValue();

            if (windowCandles.isEmpty()) continue;

            windowCandles.sort(Comparator.comparing(ApiCandle::getTimestampMillis));

            double open = windowCandles.get(0).open;
            double close = windowCandles.get(windowCandles.size() - 1).close;
            double high = windowCandles.stream().mapToDouble(c -> c.high).max().orElse(0);
            double low = windowCandles.stream().mapToDouble(c -> c.low).min().orElse(0);
            long volume = windowCandles.stream().mapToLong(c -> c.volume).sum();

            UnifiedCandle candle = UnifiedCandle.builder()
                    .scripCode(scripCode)
                    .exchange(exchange)
                    .exchangeType(exchangeType)
                    .timeframe(timeframe)
                    .windowStartMillis(windowStart)
                    .windowEndMillis(windowStart + periodMs)
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .volume(volume)
                    .build();

            candle.updateHumanReadableTimestamps();
            result.add(candle);
        }

        return result;
    }

    /**
     * Aggregate 1m candles to daily candles by calendar date.
     */
    private List<UnifiedCandle> aggregateToDailyCandles(List<ApiCandle> candles1m, String scripCode,
                                                        String exchange, String exchangeType) {
        List<UnifiedCandle> result = new ArrayList<>();

        if (candles1m.isEmpty()) return result;

        candles1m.sort(Comparator.comparing(c -> c.datetime));

        Map<LocalDate, List<ApiCandle>> dailyGroups = new LinkedHashMap<>();
        for (ApiCandle c : candles1m) {
            long ts = c.getTimestampMillis();
            if (ts <= 0) continue;
            LocalDate date = Instant.ofEpochMilli(ts).atZone(IST).toLocalDate();
            dailyGroups.computeIfAbsent(date, k -> new ArrayList<>()).add(c);
        }

        for (Map.Entry<LocalDate, List<ApiCandle>> entry : dailyGroups.entrySet()) {
            LocalDate date = entry.getKey();
            List<ApiCandle> dayCandles = entry.getValue();

            if (dayCandles.isEmpty()) continue;

            dayCandles.sort(Comparator.comparing(ApiCandle::getTimestampMillis));

            double open = dayCandles.get(0).open;
            double close = dayCandles.get(dayCandles.size() - 1).close;
            double high = dayCandles.stream().mapToDouble(c -> c.high).max().orElse(0);
            double low = dayCandles.stream().mapToDouble(c -> c.low).min().orElse(0);
            long volume = dayCandles.stream().mapToLong(c -> c.volume).sum();

            long windowStart = date.atTime(9, 15).atZone(IST).toInstant().toEpochMilli();
            long windowEnd = date.atTime(15, 30).atZone(IST).toInstant().toEpochMilli();

            UnifiedCandle candle = UnifiedCandle.builder()
                    .scripCode(scripCode)
                    .exchange(exchange)
                    .exchangeType(exchangeType)
                    .timeframe("1d")
                    .windowStartMillis(windowStart)
                    .windowEndMillis(windowEnd)
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .volume(volume)
                    .build();

            candle.updateHumanReadableTimestamps();
            result.add(candle);
        }

        return result;
    }

    /**
     * Convert API candle to UnifiedCandle.
     */
    private UnifiedCandle convertApiCandle(ApiCandle ac, String scripCode,
                                           String exchange, String exchangeType, String timeframe) {
        UnifiedCandle candle = UnifiedCandle.builder()
                .scripCode(scripCode)
                .exchange(exchange)
                .exchangeType(exchangeType)
                .timeframe(timeframe)
                .windowStartMillis(ac.getTimestampMillis())
                .windowEndMillis(ac.getTimestampMillis() + 60000)
                .open(ac.open)
                .high(ac.high)
                .low(ac.low)
                .close(ac.close)
                .volume(ac.volume)
                .build();

        candle.updateHumanReadableTimestamps();
        return candle;
    }

    private String buildKey(String scripCode, String timeframe) {
        return KEY_PREFIX + normalizeTimeframe(timeframe) + ":" + scripCode;
    }

    /**
     * Normalize timeframe to standard format.
     * HtfCandleAggregator uses "daily" but we store as "1d".
     */
    private String normalizeTimeframe(String timeframe) {
        if (timeframe == null) return "1m";
        return switch (timeframe.toLowerCase()) {
            case "daily", "day", "d" -> "1d";
            case "hour", "hourly" -> "1h";
            default -> timeframe;
        };
    }

    // ======================== API RESPONSE MODEL ========================

    @lombok.Data
    @lombok.NoArgsConstructor
    public static class ApiCandle {
        @com.fasterxml.jackson.annotation.JsonProperty("Datetime")
        @com.fasterxml.jackson.annotation.JsonAlias("datetime")
        private String datetime;

        @com.fasterxml.jackson.annotation.JsonProperty("Open")
        @com.fasterxml.jackson.annotation.JsonAlias("open")
        private double open;

        @com.fasterxml.jackson.annotation.JsonProperty("High")
        @com.fasterxml.jackson.annotation.JsonAlias("high")
        private double high;

        @com.fasterxml.jackson.annotation.JsonProperty("Low")
        @com.fasterxml.jackson.annotation.JsonAlias("low")
        private double low;

        @com.fasterxml.jackson.annotation.JsonProperty("Close")
        @com.fasterxml.jackson.annotation.JsonAlias("close")
        private double close;

        @com.fasterxml.jackson.annotation.JsonProperty("Volume")
        @com.fasterxml.jackson.annotation.JsonAlias("volume")
        private long volume;

        private static final java.time.format.DateTimeFormatter FORMATTER =
                java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME;

        public long getTimestampMillis() {
            if (datetime == null || datetime.isEmpty()) return 0;
            try {
                java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(datetime, FORMATTER);
                return ldt.atZone(IST).toInstant().toEpochMilli();
            } catch (Exception e) {
                return 0;
            }
        }
    }
}
