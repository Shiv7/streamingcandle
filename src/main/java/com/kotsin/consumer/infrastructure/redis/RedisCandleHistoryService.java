package com.kotsin.consumer.infrastructure.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.model.UnifiedCandle;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * RedisCandleHistoryService - Single source of truth for historical candle data
 * 
 * Features:
 * 1. On-demand bootstrap from 5paisa API (fetches 40 days of 1m candles)
 * 2. Real-time candle storage from Kafka streams
 * 3. Automatic aggregation from 1m to higher timeframes (5m, 15m, 30m)
 * 4. Redis sorted sets for efficient time-based queries
 * 
 * Redis Keys:
 * - candles:1m:{scripCode} -> Sorted set (score=timestamp, value=JSON)
 * - candles:30m:{scripCode} -> Sorted set (score=timestamp, value=JSON)
 * 
 * Used by:
 * - MasterArchProcessor (needs 55+ 30m candles)
 * - BBSuperTrendDetector
 * - StructureTracker
 * - VCPProcessor
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisCandleHistoryService {

    private final StringRedisTemplate stringRedisTemplate;
    
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
    
    @Value("${history.base-url:http://localhost:8002}")
    private String historyBaseUrl;
    
    @Value("${candle.history.bootstrap.days:40}")
    private int bootstrapDays;
    
    @Value("${candle.history.max.candles:500}")
    private int maxCandlesPerKey;
    
    private ZSetOperations<String, String> zSetOps;
    
    // Track which scripCodes have been bootstrapped (to avoid repeated API calls)
    private final Set<String> bootstrappedScripCodes = ConcurrentHashMap.newKeySet();
    
    private static final String KEY_PREFIX = "candles:";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    
    @PostConstruct
    public void init() {
        this.zSetOps = stringRedisTemplate.opsForZSet();
        log.info("RedisCandleHistoryService initialized (bootstrap={}days, max={}candles)", 
                bootstrapDays, maxCandlesPerKey);
    }
    
    // ======================== PUBLIC API ========================
    
    /**
     * Get historical candles for a scripCode and timeframe.
     * Automatically triggers bootstrap if insufficient data.
     * 
     * @param scripCode Scrip code (e.g., "14154")
     * @param timeframe Timeframe ("1m", "5m", "15m", "30m")
     * @param count Number of candles to retrieve
     * @param exchange Exchange code for bootstrap (e.g., "N" for NSE)
     * @param exchangeType Exchange type for bootstrap (e.g., "C" for Cash)
     * @return List of UnifiedCandle (most recent last)
     */
    public List<UnifiedCandle> getCandles(String scripCode, String timeframe, int count,
                                          String exchange, String exchangeType) {
        String key = buildKey(scripCode, timeframe);
        
        // Check if we have enough data
        Long size = zSetOps.zCard(key);
        if (size == null || size < count) {
            // Trigger bootstrap if not already done
            if (!bootstrappedScripCodes.contains(scripCode)) {
                bootstrapFromApi(scripCode, exchange, exchangeType);
            }
        }
        
        // Fetch from Redis (reverse range to get most recent, then reverse to get chronological order)
        Set<String> jsonSet = zSetOps.reverseRange(key, 0, count - 1);
        if (jsonSet == null || jsonSet.isEmpty()) {
            log.debug("No candles found for key: {}", key);
            return Collections.emptyList();
        }
        
        List<UnifiedCandle> candles = new ArrayList<>();
        for (String json : jsonSet) {
            try {
                candles.add(mapper.readValue(json, UnifiedCandle.class));
            } catch (Exception e) {
                log.warn("Failed to deserialize candle: {}", e.getMessage());
            }
        }
        
        // Reverse to chronological order (oldest first, newest last)
        Collections.reverse(candles);
        return candles;
    }
    
    /**
     * Store a new candle in Redis (called from real-time stream processors)
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
            
            log.trace("Stored candle for {} {} at {}", candle.getScripCode(), 
                    candle.getTimeframe(), candle.getWindowStartMillis());
        } catch (Exception e) {
            log.warn("Failed to store candle for {}: {}", candle.getScripCode(), e.getMessage());
        }
    }
    
    /**
     * Check if a scripCode has sufficient history for calculations
     * 
     * @param scripCode Scrip code
     * @param timeframe Timeframe
     * @param requiredCount Minimum candles required
     * @return true if sufficient data exists
     */
    public boolean hasSufficientHistory(String scripCode, String timeframe, int requiredCount) {
        String key = buildKey(scripCode, timeframe);
        Long size = zSetOps.zCard(key);
        return size != null && size >= requiredCount;
    }
    
    /**
     * Get the count of candles in Redis for a scripCode/timeframe
     */
    public long getCandleCount(String scripCode, String timeframe) {
        String key = buildKey(scripCode, timeframe);
        Long size = zSetOps.zCard(key);
        return size != null ? size : 0;
    }
    
    // ======================== BOOTSTRAP LOGIC ========================
    
    /**
     * Bootstrap candles from 5paisa API for a scripCode
     * Fetches last N days of 1m candles and aggregates to higher timeframes
     */
    public void bootstrapFromApi(String scripCode, String exchange, String exchangeType) {
        if (bootstrappedScripCodes.contains(scripCode)) {
            log.debug("ScripCode {} already bootstrapped", scripCode);
            return;
        }
        
        LocalDate endDate = LocalDate.now(IST);
        LocalDate startDate = endDate.minusDays(bootstrapDays);
        
        log.info("Bootstrapping candles for {} from {} to {} ({}:{})...", 
                scripCode, startDate, endDate, exchange, exchangeType);
        
        try {
            List<ApiCandle> apiCandles = fetchFromApi(scripCode, startDate, endDate, exchange, exchangeType);
            
            if (apiCandles.isEmpty()) {
                log.warn("No candles returned from API for {}", scripCode);
                bootstrappedScripCodes.add(scripCode);  // Mark as done to avoid retry
                return;
            }
            
            // Store 1m candles
            int stored1m = 0;
            for (ApiCandle ac : apiCandles) {
                UnifiedCandle candle = convertApiCandle(ac, scripCode, exchange, exchangeType, "1m");
                storeCandle(candle);
                stored1m++;
            }
            
            // Aggregate to 30m
            List<UnifiedCandle> candles30m = aggregate1mTo30m(apiCandles, scripCode, exchange, exchangeType);
            for (UnifiedCandle c : candles30m) {
                storeCandle(c);
            }
            
            bootstrappedScripCodes.add(scripCode);
            log.info("✅ Bootstrapped {} 1m candles, {} 30m candles for {}", 
                    stored1m, candles30m.size(), scripCode);
            
        } catch (Exception e) {
            log.error("Failed to bootstrap candles for {}: {}", scripCode, e.getMessage());
            bootstrappedScripCodes.add(scripCode);  // Mark as done to avoid retry loops
        }
    }
    
    /**
     * Fetch candles from 5paisa historical API
     */
    private List<ApiCandle> fetchFromApi(String scripCode, LocalDate startDate, LocalDate endDate,
                                          String exchange, String exchangeType) {
        try {
            HttpUrl url = HttpUrl.parse(historyBaseUrl)
                    .newBuilder()
                    .addPathSegments("getHisDataFromFivePaisa")
                    .addQueryParameter("scrip_code", scripCode)
                    .addQueryParameter("start_date", startDate.toString())
                    .addQueryParameter("end_date", endDate.plusDays(1).toString())  // API needs +1 day
                    .addQueryParameter("exch", exchange.toLowerCase())
                    .addQueryParameter("exch_type", exchangeType.toLowerCase())
                    .addQueryParameter("interval", "1m")
                    .build();
            
            log.debug("Fetching historical candles: {}", url);
            Request req = new Request.Builder().url(url).get().build();
            
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    log.warn("API returned non-200 for {}: {}", scripCode, resp.code());
                    return Collections.emptyList();
                }
                
                List<ApiCandle> candles = mapper.readValue(resp.body().byteStream(), 
                        new TypeReference<List<ApiCandle>>(){});
                log.debug("Fetched {} candles for {} from API", candles.size(), scripCode);
                return candles;
            }
        } catch (Exception e) {
            log.error("API fetch failed for {}: {}", scripCode, e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * Aggregate 1m candles to 30m candles
     */
    private List<UnifiedCandle> aggregate1mTo30m(List<ApiCandle> candles1m, String scripCode,
                                                  String exchange, String exchangeType) {
        List<UnifiedCandle> candles30m = new ArrayList<>();
        
        // Sort by datetime
        candles1m.sort(Comparator.comparing(c -> c.datetime));
        
        // Group by 30-minute window
        Map<Long, List<ApiCandle>> windows = new LinkedHashMap<>();
        for (ApiCandle c : candles1m) {
            long windowStart = (c.getTimestampMillis() / (30 * 60 * 1000)) * (30 * 60 * 1000);
            windows.computeIfAbsent(windowStart, k -> new ArrayList<>()).add(c);
        }
        
        // Create 30m candles
        for (Map.Entry<Long, List<ApiCandle>> entry : windows.entrySet()) {
            long windowStart = entry.getKey();
            List<ApiCandle> windowCandles = entry.getValue();
            
            if (windowCandles.isEmpty()) continue;
            
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
                    .windowEndMillis(windowStart + 30 * 60 * 1000)
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
     * Convert API candle to UnifiedCandle
     */
    private UnifiedCandle convertApiCandle(ApiCandle ac, String scripCode, 
                                           String exchange, String exchangeType, String timeframe) {
        UnifiedCandle candle = UnifiedCandle.builder()
                .scripCode(scripCode)
                .exchange(exchange)
                .exchangeType(exchangeType)
                .timeframe(timeframe)
                .windowStartMillis(ac.getTimestampMillis())
                .windowEndMillis(ac.getTimestampMillis() + 60000)  // +1 min for 1m candles
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
        return KEY_PREFIX + timeframe + ":" + scripCode;
    }
    
    // ======================== API RESPONSE MODEL ========================
    
    /**
     * Model for 5paisa API response candle
     */
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
