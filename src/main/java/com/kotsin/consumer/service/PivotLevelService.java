package com.kotsin.consumer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kotsin.consumer.client.FastAnalyticsClient;
import com.kotsin.consumer.model.MultiTimeframePivotState;
import com.kotsin.consumer.model.PivotLevels;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;

/**
 * PivotLevelService - Manages multi-timeframe pivot levels for all symbols.
 *
 * Fetches and caches:
 * - Daily pivots (today and yesterday)
 * - Weekly pivots (this week and last week)
 * - Monthly pivots (this month and last month)
 */
@Service
@Slf4j
public class PivotLevelService {

    private static final String LOG_PREFIX = "[PIVOT-LEVEL]";
    private static final String REDIS_KEY_PREFIX = "pivot:mtf:";

    @Autowired
    private FastAnalyticsClient fastAnalyticsClient;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${pivot.cache.ttl-minutes:60}")
    private int cacheTtlMinutes;

    @Value("${pivot.confluence.threshold-percent:0.5}")
    private double confluenceThresholdPercent;

    private final ObjectMapper objectMapper;

    // In-memory cache for quick access
    private final ConcurrentHashMap<String, MultiTimeframePivotState> pivotCache = new ConcurrentHashMap<>();

    // Executor for async pivot loading
    private final ExecutorService pivotExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "pivot-loader");
        t.setDaemon(true);
        return t;
    });

    public PivotLevelService() {
        // Initialize ObjectMapper with Java 8 time support
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @PostConstruct
    public void init() {
        log.info("{} Initialized with cacheTtl={}min, confluenceThreshold={}%",
            LOG_PREFIX, cacheTtlMinutes, confluenceThresholdPercent);
    }

    /**
     * Load all pivot levels for a symbol (synchronous).
     */
    public MultiTimeframePivotState loadPivotLevels(String scripCode, String exch, String exchType) {
        log.debug("{} Loading pivot levels for scripCode={}", LOG_PREFIX, scripCode);

        try {
            LocalDate today = LocalDate.now();

            // Calculate date ranges for each timeframe
            DateRanges ranges = calculateDateRanges(today);

            // Fetch all pivots
            PivotLevels dailyPivot = fastAnalyticsClient.getPivotData(
                exch, exchType, scripCode, ranges.dailyStart, ranges.dailyEnd, "1d");
            PivotLevels prevDailyPivot = fastAnalyticsClient.getPivotData(
                exch, exchType, scripCode, ranges.prevDailyStart, ranges.prevDailyEnd, "1d");
            PivotLevels weeklyPivot = fastAnalyticsClient.getPivotData(
                exch, exchType, scripCode, ranges.weeklyStart, ranges.weeklyEnd, "1wk");
            PivotLevels prevWeeklyPivot = fastAnalyticsClient.getPivotData(
                exch, exchType, scripCode, ranges.prevWeeklyStart, ranges.prevWeeklyEnd, "1wk");
            PivotLevels monthlyPivot = fastAnalyticsClient.getPivotData(
                exch, exchType, scripCode, ranges.monthlyStart, ranges.monthlyEnd, "1mo");

            MultiTimeframePivotState state = MultiTimeframePivotState.builder()
                .symbol(scripCode)
                .scripCode(scripCode)
                .exchange(exch)
                .exchangeType(exchType)
                .dailyPivot(dailyPivot)
                .prevDailyPivot(prevDailyPivot)
                .weeklyPivot(weeklyPivot)
                .prevWeeklyPivot(prevWeeklyPivot)
                .monthlyPivot(monthlyPivot)
                .lastUpdated(Instant.now())
                .build();

            // Cache in memory
            pivotCache.put(scripCode, state);

            // Cache in Redis
            cacheToRedis(scripCode, state);

            // Log CPR analysis
            if (dailyPivot.getPivot() > 0) {
                double cprWidth = dailyPivot.getCprWidthPercent();
                String cprType = cprWidth < 0.3 ? "ULTRA-THIN" :
                                 cprWidth < 0.5 ? "THIN" :
                                 cprWidth < 1.0 ? "NORMAL" : "WIDE";

                log.info("{} Loaded pivots for {} - Daily CPR: {}% ({}) | Pivot: {} | TC: {} | BC: {}",
                    LOG_PREFIX, scripCode,
                    String.format("%.3f", cprWidth), cprType,
                    String.format("%.2f", dailyPivot.getPivot()),
                    String.format("%.2f", dailyPivot.getTc()),
                    String.format("%.2f", dailyPivot.getBc()));
            }

            return state;

        } catch (Exception e) {
            log.error("{} Failed to load pivot levels for {}: {}",
                LOG_PREFIX, scripCode, e.getMessage());
            return null;
        }
    }

    /**
     * Load pivot levels asynchronously.
     */
    public CompletableFuture<MultiTimeframePivotState> loadPivotLevelsAsync(
            String scripCode, String exch, String exchType) {
        return CompletableFuture.supplyAsync(
            () -> loadPivotLevels(scripCode, exch, exchType),
            pivotExecutor
        );
    }

    /**
     * Get cached pivot levels for a symbol.
     */
    public Optional<MultiTimeframePivotState> getPivotLevels(String scripCode) {
        // Try memory cache first
        MultiTimeframePivotState cached = pivotCache.get(scripCode);
        if (cached != null && !cached.needsRefresh(cacheTtlMinutes)) {
            return Optional.of(cached);
        }

        // Try Redis cache
        Optional<MultiTimeframePivotState> fromRedis = loadFromRedis(scripCode);
        if (fromRedis.isPresent()) {
            pivotCache.put(scripCode, fromRedis.get());
            return fromRedis;
        }

        return Optional.empty();
    }

    /**
     * Get or load pivot levels (loads if not cached).
     */
    public Optional<MultiTimeframePivotState> getOrLoadPivotLevels(
            String scripCode, String exch, String exchType) {

        Optional<MultiTimeframePivotState> cached = getPivotLevels(scripCode);
        if (cached.isPresent()) {
            return cached;
        }

        // Load synchronously
        MultiTimeframePivotState state = loadPivotLevels(scripCode, exch, exchType);
        return Optional.ofNullable(state);
    }

    /**
     * Refresh pivot levels for a symbol.
     */
    public void refreshPivotLevels(String scripCode, String exch, String exchType) {
        pivotCache.remove(scripCode);
        redisTemplate.delete(REDIS_KEY_PREFIX + scripCode);
        loadPivotLevelsAsync(scripCode, exch, exchType);
    }

    /**
     * Refresh all cached pivots (scheduled task).
     */
    @Scheduled(cron = "${pivot.refresh.cron:0 0 9 * * MON-FRI}")
    public void refreshAllPivots() {
        log.info("{} Starting scheduled pivot refresh for {} symbols",
            LOG_PREFIX, pivotCache.size());

        pivotCache.forEach((scripCode, state) -> {
            if (state != null) {
                loadPivotLevelsAsync(scripCode, state.getExchange(), state.getExchangeType());
            }
        });
    }

    /**
     * Calculate date ranges for pivot calculations.
     */
    private DateRanges calculateDateRanges(LocalDate today) {
        DateRanges ranges = new DateRanges();
        DateTimeFormatter fmt = DateTimeFormatter.ISO_DATE;

        // Daily: Use yesterday's data for today's pivots
        ranges.dailyEnd = today.format(fmt);
        ranges.dailyStart = today.minusDays(1).format(fmt);
        ranges.prevDailyEnd = today.minusDays(1).format(fmt);
        ranges.prevDailyStart = today.minusDays(2).format(fmt);

        // Weekly: Use last week's data for this week's pivots
        LocalDate lastWeekEnd = today.with(TemporalAdjusters.previous(DayOfWeek.SUNDAY));
        LocalDate lastWeekStart = lastWeekEnd.minusDays(6);
        ranges.weeklyEnd = lastWeekEnd.plusDays(1).format(fmt);
        ranges.weeklyStart = lastWeekStart.format(fmt);
        ranges.prevWeeklyEnd = lastWeekStart.format(fmt);
        ranges.prevWeeklyStart = lastWeekStart.minusDays(7).format(fmt);

        // Monthly: Use last month's data for this month's pivots
        LocalDate lastMonthEnd = today.withDayOfMonth(1).minusDays(1);
        LocalDate lastMonthStart = lastMonthEnd.withDayOfMonth(1);
        ranges.monthlyEnd = today.withDayOfMonth(1).format(fmt);
        ranges.monthlyStart = lastMonthStart.format(fmt);

        return ranges;
    }

    /**
     * Cache pivot state to Redis.
     */
    private void cacheToRedis(String scripCode, MultiTimeframePivotState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(
                REDIS_KEY_PREFIX + scripCode,
                json,
                cacheTtlMinutes,
                TimeUnit.MINUTES
            );
        } catch (Exception e) {
            log.warn("{} Failed to cache to Redis for {}: {}",
                LOG_PREFIX, scripCode, e.getMessage());
        }
    }

    /**
     * Load pivot state from Redis.
     */
    private Optional<MultiTimeframePivotState> loadFromRedis(String scripCode) {
        try {
            String json = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + scripCode);
            if (json != null && !json.isEmpty()) {
                MultiTimeframePivotState state = objectMapper.readValue(
                    json, MultiTimeframePivotState.class);
                return Optional.of(state);
            }
        } catch (Exception e) {
            log.warn("{} Failed to load from Redis for {}: {}",
                LOG_PREFIX, scripCode, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Get all cached symbol codes.
     */
    public Set<String> getCachedSymbols() {
        return pivotCache.keySet();
    }

    /**
     * Get cache statistics.
     */
    public CacheStats getStats() {
        int memorySize = pivotCache.size();
        int validCount = (int) pivotCache.values().stream()
            .filter(s -> s != null && s.isValid())
            .count();
        int thinCprCount = (int) pivotCache.values().stream()
            .filter(s -> s != null && s.isDailyCprThin())
            .count();
        int ultraThinCprCount = (int) pivotCache.values().stream()
            .filter(s -> s != null && s.isDailyCprUltraThin())
            .count();

        return new CacheStats(memorySize, validCount, thinCprCount, ultraThinCprCount);
    }

    /**
     * Date ranges holder.
     */
    private static class DateRanges {
        String dailyStart, dailyEnd;
        String prevDailyStart, prevDailyEnd;
        String weeklyStart, weeklyEnd;
        String prevWeeklyStart, prevWeeklyEnd;
        String monthlyStart, monthlyEnd;
    }

    /**
     * Cache statistics record.
     */
    public record CacheStats(int cacheSize, int validCount, int thinCprCount, int ultraThinCprCount) {}
}
