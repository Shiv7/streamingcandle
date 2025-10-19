package com.kotsin.consumer.service;

import com.kotsin.consumer.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service to cache instrument families (equity + future + options)
 * Fetches from scripFinder API and caches in Redis + LocalHashMap
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InstrumentFamilyCacheService {
    
    private final RestTemplate restTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private final Map<String, InstrumentFamily> localCache = new ConcurrentHashMap<>();
    
    @Value("${instrument.api.base.url:http://13.203.60.173:8102}")
    private String apiBaseUrl;
    
    @Value("${instrument.cache.parallel.threads:10}")
    private int parallelThreads;
    
    private static final String CACHE_KEY_PREFIX = "instrument:family:";
    private static final String CACHE_KEY_ALL = "instrument:families:all";
    private static final Duration CACHE_TTL = Duration.ofDays(1);
    
    @PostConstruct
    public void initializeCache() {
        log.info("🚀 Initializing instrument family cache...");
        try {
            refreshCache();
            log.info("✅ Instrument family cache initialized successfully");
        } catch (Exception e) {
            log.error("❌ Failed to initialize instrument family cache", e);
        }
    }
    
    /**
     * Daily refresh at 3 AM IST
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void dailyRefresh() {
        log.info("🔄 Daily cache refresh started...");
        refreshCache();
    }
    
    /**
     * Manual cache refresh
     */
    public void refreshCache() {
        try {
            long startTime = System.currentTimeMillis();
            
            // 1. Get all equity scripCodes
            List<String> scripCodes = fetchAllEquityScripCodes();
            log.info("📊 Found {} equity scripCodes", scripCodes.size());
            
            if (scripCodes.isEmpty()) {
                log.warn("⚠️ No equity scripCodes found, skipping cache refresh");
                return;
            }
            
            // 2. Build families in parallel batches
            Map<String, InstrumentFamily> families = buildFamiliesInBatches(scripCodes);
            
            // 3. Store in Redis
            storeInRedis(families);
            
            // 4. Update local cache
            localCache.clear();
            localCache.putAll(families);
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("✅ Cache refresh complete. Loaded {} families in {}ms", 
                families.size(), duration);
            
        } catch (Exception e) {
            log.error("❌ Cache refresh failed", e);
        }
    }
    
    private List<String> fetchAllEquityScripCodes() {
        String url = apiBaseUrl + "/getDesiredWebSocket?tradingType=EQUITY";
        
        try {
            EquityListResponse response = restTemplate.getForObject(url, EquityListResponse.class);
            
            if (response == null || !response.isSuccess()) {
                throw new RuntimeException("Failed to fetch equity list: " + 
                    (response != null ? response.getMessage() : "null response"));
            }
            
            return response.getResponse().stream()
                .map(EquityData::getScripCodeForApi)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
                
        } catch (Exception e) {
            log.error("❌ Failed to fetch equity scripCodes from: {}", url, e);
            throw new RuntimeException("Failed to fetch equity scripCodes", e);
        }
    }
    
    private Map<String, InstrumentFamily> buildFamiliesInBatches(List<String> scripCodes) {
        Map<String, InstrumentFamily> families = new ConcurrentHashMap<>();
        
        // Process in batches to avoid overwhelming the API
        int batchSize = Math.max(1, scripCodes.size() / parallelThreads);
        List<List<String>> batches = partitionList(scripCodes, batchSize);
        
        log.info("📦 Processing {} scripCodes in {} batches of size {}", 
            scripCodes.size(), batches.size(), batchSize);
        
        batches.parallelStream().forEach(batch -> {
            for (String scripCode : batch) {
                try {
                    InstrumentFamily family = buildInstrumentFamily(scripCode);
                    if (family != null) {
                        families.put(scripCode, family);
                    }
                } catch (Exception e) {
                    log.error("❌ Failed to build family for scripCode: {}", scripCode, e);
                }
            }
        });
        
        return families;
    }
    
    private InstrumentFamily buildInstrumentFamily(String scripCode) {
        try {
            // Get future
            FutureResponse futureResp = getFutureData(scripCode);
            
            // Get options
            OptionsResponse optionsResp = getOptionsData(scripCode);
            
            return InstrumentFamily.builder()
                .equityScripCode(scripCode)
                .companyName(futureResp.getEquity() != null ? futureResp.getEquity().getName() : "Unknown")
                .equity(futureResp.getEquity())
                .future(futureResp.getFuture())
                .options(optionsResp.getOptions())
                .lastUpdated(System.currentTimeMillis())
                .dataSource("API")
                .build();
                
        } catch (Exception e) {
            log.error("❌ Failed to build family for scripCode: {}", scripCode, e);
            return InstrumentFamily.builder()
                .equityScripCode(scripCode)
                .companyName("Unknown")
                .lastUpdated(System.currentTimeMillis())
                .dataSource("ERROR")
                .build();
        }
    }
    
    private FutureResponse getFutureData(String scripCode) {
        String url = apiBaseUrl + "/getRequiredFuture?equityScripCode=" + scripCode;
        
        try {
            FutureResponse response = restTemplate.getForObject(url, FutureResponse.class);
            if (response == null || !response.isSuccess()) {
                log.warn("⚠️ Future API failed for scripCode: {} - {}", scripCode, 
                    response != null ? response.getMessage() : "null response");
                return FutureResponse.builder()
                    .status(500)
                    .message("API call failed")
                    .build();
            }
            return response;
        } catch (Exception e) {
            log.error("❌ Future API error for scripCode: {}", scripCode, e);
            return FutureResponse.builder()
                .status(500)
                .message("API call failed: " + e.getMessage())
                .build();
        }
    }
    
    private OptionsResponse getOptionsData(String scripCode) {
        String url = apiBaseUrl + "/getRequiredOptions?equityScripCode=" + scripCode;
        
        try {
            OptionsResponse response = restTemplate.getForObject(url, OptionsResponse.class);
            if (response == null || !response.isSuccess()) {
                log.warn("⚠️ Options API failed for scripCode: {} - {}", scripCode,
                    response != null ? response.getMessage() : "null response");
                return OptionsResponse.builder()
                    .status(500)
                    .message("API call failed")
                    .options(Collections.emptyList())
                    .build();
            }
            return response;
        } catch (Exception e) {
            log.error("❌ Options API error for scripCode: {}", scripCode, e);
            return OptionsResponse.builder()
                .status(500)
                .message("API call failed: " + e.getMessage())
                .options(Collections.emptyList())
                .build();
        }
    }
    
    private void storeInRedis(Map<String, InstrumentFamily> families) {
        try {
            // Store individual families
            families.forEach((scripCode, family) -> {
                redisTemplate.opsForValue().set(
                    CACHE_KEY_PREFIX + scripCode,
                    family,
                    CACHE_TTL
                );
            });
            
            // Store all families list
            redisTemplate.opsForValue().set(
                CACHE_KEY_ALL,
                new ArrayList<>(families.keySet()),
                CACHE_TTL
            );
            
            log.info("💾 Stored {} families in Redis", families.size());
            
        } catch (Exception e) {
            log.error("❌ Failed to store families in Redis", e);
        }
    }
    
    /**
     * Get instrument family by scripCode
     */
    public InstrumentFamily getFamily(String scripCode) {
        if (scripCode == null || scripCode.trim().isEmpty()) {
            return null;
        }
        
        // Try local cache first
        InstrumentFamily family = localCache.get(scripCode);
        if (family != null) {
            return family;
        }
        
        // Fallback to Redis
        try {
            family = (InstrumentFamily) redisTemplate.opsForValue().get(CACHE_KEY_PREFIX + scripCode);
            if (family != null) {
                localCache.put(scripCode, family);
                return family;
            }
        } catch (Exception e) {
            log.error("❌ Redis read error for scripCode: {}", scripCode, e);
        }
        
        // Last resort: build on demand
        log.warn("⚠️ Building family on demand for: {}", scripCode);
        family = buildInstrumentFamily(scripCode);
        if (family != null) {
            localCache.put(scripCode, family);
        }
        return family;
    }
    
    /**
     * Get all cached families
     */
    public Map<String, InstrumentFamily> getAllFamilies() {
        return new HashMap<>(localCache);
    }
    
    /**
     * Get cache size
     */
    public int getCacheSize() {
        return localCache.size();
    }
    
    /**
     * Get cache statistics
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("cacheSize", localCache.size());
        stats.put("familiesWithFutures", localCache.values().stream()
            .mapToInt(f -> f.hasFuture() ? 1 : 0)
            .sum());
        stats.put("familiesWithOptions", localCache.values().stream()
            .mapToInt(f -> f.hasOptions() ? 1 : 0)
            .sum());
        stats.put("totalOptions", localCache.values().stream()
            .mapToInt(InstrumentFamily::getOptionsCount)
            .sum());
        stats.put("errorFamilies", localCache.values().stream()
            .mapToInt(f -> "ERROR".equals(f.getDataSource()) ? 1 : 0)
            .sum());
        return stats;
    }
    
    /**
     * Clear cache
     */
    public void clearCache() {
        localCache.clear();
        log.info("🗑️ Local cache cleared");
    }
    
    private <T> List<List<T>> partitionList(List<T> list, int batchSize) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += batchSize) {
            partitions.add(list.subList(i, Math.min(i + batchSize, list.size())));
        }
        return partitions;
    }
}
