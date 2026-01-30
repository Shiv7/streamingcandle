package com.kotsin.consumer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.dto.TechnicalIndicatorDTO;
import com.kotsin.consumer.enrichment.enricher.TechnicalIndicatorEnricher;
import com.kotsin.consumer.enrichment.model.TechnicalContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.*;

/**
 * Service for retrieving and caching technical indicator data.
 * Provides Bollinger Bands, VWAP, and SuperTrend indicators for the dashboard.
 *
 * Uses Redis for persistent caching to survive service restarts.
 */
@Slf4j
@Service
public class TechnicalIndicatorService {

    private final RedisTemplate<String, String> redisTemplate;
    private final TechnicalIndicatorEnricher technicalIndicatorEnricher;
    private final ObjectMapper objectMapper;

    // Redis key prefixes
    private static final String INDICATOR_KEY_PREFIX = "tech:indicator:";
    private static final String HISTORY_KEY_PREFIX = "tech:history:";
    private static final String CACHE_KEY_SEPARATOR = ":";

    // Cache settings
    private static final int MAX_HISTORY_SIZE = 100;
    private static final Duration CACHE_TTL = Duration.ofHours(48); // Indicators expire after 48 hours

    public TechnicalIndicatorService(
            RedisTemplate<String, String> redisTemplate,
            TechnicalIndicatorEnricher technicalIndicatorEnricher) {
        this.redisTemplate = redisTemplate;
        this.technicalIndicatorEnricher = technicalIndicatorEnricher;
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void init() {
        log.info("[TECH_SVC] TechnicalIndicatorService initialized with Redis-backed persistent cache (TTL={}h)",
                CACHE_TTL.toHours());
    }

    /**
     * Get latest technical indicators for a scrip at a given timeframe.
     *
     * @param scripCode Scrip code
     * @param timeframe Timeframe (e.g., "5m", "15m")
     * @return TechnicalIndicatorDTO or null if not available
     */
    public TechnicalIndicatorDTO getIndicators(String scripCode, String timeframe) {
        String redisKey = buildIndicatorKey(scripCode, timeframe);
        try {
            String json = redisTemplate.opsForValue().get(redisKey);
            if (json == null || json.isEmpty()) {
                return null;
            }
            return objectMapper.readValue(json, TechnicalIndicatorDTO.class);
        } catch (Exception e) {
            log.warn("[TECH_SVC] Error reading indicator from Redis for {}: {}", redisKey, e.getMessage());
            return null;
        }
    }

    /**
     * Get technical indicators for multiple scrips (batch fetch).
     *
     * @param scripCodes List of scrip codes
     * @param timeframe Timeframe
     * @return Map of scripCode -> TechnicalIndicatorDTO
     */
    public Map<String, TechnicalIndicatorDTO> getIndicatorsForWatchlist(List<String> scripCodes, String timeframe) {
        Map<String, TechnicalIndicatorDTO> result = new HashMap<>();

        // Build all keys
        List<String> keys = new ArrayList<>();
        for (String scripCode : scripCodes) {
            keys.add(buildIndicatorKey(scripCode, timeframe));
        }

        // Batch fetch from Redis
        try {
            List<String> values = redisTemplate.opsForValue().multiGet(keys);
            if (values != null) {
                for (int i = 0; i < scripCodes.size(); i++) {
                    String json = values.get(i);
                    if (json != null && !json.isEmpty()) {
                        try {
                            TechnicalIndicatorDTO dto = objectMapper.readValue(json, TechnicalIndicatorDTO.class);
                            result.put(scripCodes.get(i), dto);
                        } catch (JsonProcessingException e) {
                            log.warn("[TECH_SVC] Error parsing indicator for {}: {}", scripCodes.get(i), e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[TECH_SVC] Error batch fetching indicators: {}", e.getMessage());
            // Fallback to individual fetches
            for (String scripCode : scripCodes) {
                TechnicalIndicatorDTO indicator = getIndicators(scripCode, timeframe);
                if (indicator != null) {
                    result.put(scripCode, indicator);
                }
            }
        }

        return result;
    }

    /**
     * Get indicator history for chart overlays.
     *
     * @param scripCode Scrip code
     * @param timeframe Timeframe
     * @param limit Maximum number of history points to return
     * @return List of TechnicalIndicatorDTO (chronologically ordered)
     */
    public List<TechnicalIndicatorDTO> getIndicatorHistory(String scripCode, String timeframe, int limit) {
        String redisKey = buildHistoryKey(scripCode, timeframe);
        try {
            // Get last 'limit' entries from the list (0 to limit-1, but list is in reverse order)
            List<String> jsonList = redisTemplate.opsForList().range(redisKey, 0, limit - 1);
            if (jsonList == null || jsonList.isEmpty()) {
                return Collections.emptyList();
            }

            List<TechnicalIndicatorDTO> history = new ArrayList<>();
            for (String json : jsonList) {
                try {
                    TechnicalIndicatorDTO dto = objectMapper.readValue(json, TechnicalIndicatorDTO.class);
                    history.add(dto);
                } catch (JsonProcessingException e) {
                    log.warn("[TECH_SVC] Error parsing history entry: {}", e.getMessage());
                }
            }

            // Reverse to get chronological order (oldest first)
            Collections.reverse(history);
            return history;
        } catch (Exception e) {
            log.warn("[TECH_SVC] Error reading history from Redis for {}: {}", redisKey, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Update indicators from a processed FamilyCandle.
     * Called by Kafka processor after candle is enriched.
     *
     * @param family FamilyCandle with technical context
     * @param context TechnicalContext from enricher
     */
    public void updateIndicators(FamilyCandle family, TechnicalContext context) {
        if (family == null || context == null) {
            return;
        }

        // Use equity candle if available, otherwise future
        InstrumentCandle candle = family.getEquity() != null ? family.getEquity() : family.getFuture();
        if (candle == null) {
            return;
        }

        TechnicalIndicatorDTO dto = TechnicalIndicatorDTO.fromTechnicalContext(context, candle);
        if (dto == null) {
            return;
        }

        String scripCode = candle.getScripCode();
        String timeframe = family.getTimeframe();

        // Store in Redis
        storeIndicator(scripCode, timeframe, dto);

        // FIX: SLF4J doesn't support {:.2f} format - use String.format for the value
        log.debug("[TECH_SVC] Updated indicators for {} [{}]: ST={} BB%B={} VWAP={}",
                scripCode, timeframe,
                context.isSuperTrendBullish() ? "BULL" : "BEAR",
                Double.isNaN(context.getBbPercentB()) ? "N/A" : String.format("%.2f", context.getBbPercentB()),
                dto.getVwapSignal());
    }

    /**
     * Update indicators from InstrumentCandle directly.
     * Alternative method when only candle data is available.
     *
     * @param candle InstrumentCandle
     * @param context TechnicalContext
     */
    public void updateIndicators(InstrumentCandle candle, TechnicalContext context) {
        if (candle == null || context == null) {
            return;
        }

        TechnicalIndicatorDTO dto = TechnicalIndicatorDTO.fromTechnicalContext(context, candle);
        if (dto == null) {
            return;
        }

        storeIndicator(candle.getScripCode(), candle.getTimeframe(), dto);
    }

    /**
     * Store indicator in Redis (both latest and history).
     */
    private void storeIndicator(String scripCode, String timeframe, TechnicalIndicatorDTO dto) {
        try {
            String json = objectMapper.writeValueAsString(dto);
            String indicatorKey = buildIndicatorKey(scripCode, timeframe);
            String historyKey = buildHistoryKey(scripCode, timeframe);

            // Store latest indicator with TTL
            redisTemplate.opsForValue().set(indicatorKey, json, CACHE_TTL);

            // Add to history list (push to front, newest first)
            redisTemplate.opsForList().leftPush(historyKey, json);

            // Trim history to max size
            redisTemplate.opsForList().trim(historyKey, 0, MAX_HISTORY_SIZE - 1);

            // Set TTL on history key
            redisTemplate.expire(historyKey, CACHE_TTL);

        } catch (JsonProcessingException e) {
            log.error("[TECH_SVC] Error serializing indicator for {}:{}: {}", scripCode, timeframe, e.getMessage());
        } catch (Exception e) {
            log.error("[TECH_SVC] Error storing indicator in Redis for {}:{}: {}", scripCode, timeframe, e.getMessage());
        }
    }

    /**
     * Get all available scrip codes with cached indicators.
     */
    public Set<String> getAvailableScrips(String timeframe) {
        Set<String> scrips = new HashSet<>();
        String pattern = INDICATOR_KEY_PREFIX + "*" + CACHE_KEY_SEPARATOR + timeframe;

        try {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null) {
                for (String key : keys) {
                    // Extract scripCode from key: tech:indicator:{scripCode}:{timeframe}
                    String withoutPrefix = key.substring(INDICATOR_KEY_PREFIX.length());
                    int lastSeparator = withoutPrefix.lastIndexOf(CACHE_KEY_SEPARATOR);
                    if (lastSeparator > 0) {
                        String scripCode = withoutPrefix.substring(0, lastSeparator);
                        scrips.add(scripCode);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[TECH_SVC] Error scanning keys from Redis: {}", e.getMessage());
        }

        return scrips;
    }

    /**
     * Get cache statistics for monitoring.
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();

        try {
            // Count indicator keys
            Set<String> indicatorKeys = redisTemplate.keys(INDICATOR_KEY_PREFIX + "*");
            int indicatorCount = indicatorKeys != null ? indicatorKeys.size() : 0;

            // Count history keys
            Set<String> historyKeys = redisTemplate.keys(HISTORY_KEY_PREFIX + "*");
            int historyCount = historyKeys != null ? historyKeys.size() : 0;

            stats.put("indicatorCacheSize", indicatorCount);
            stats.put("historyCacheSize", historyCount);
            stats.put("cacheType", "Redis");
            stats.put("ttlHours", CACHE_TTL.toHours());

            // Count entries by timeframe
            Map<String, Integer> byTimeframe = new HashMap<>();
            if (indicatorKeys != null) {
                for (String key : indicatorKeys) {
                    String withoutPrefix = key.substring(INDICATOR_KEY_PREFIX.length());
                    int lastSeparator = withoutPrefix.lastIndexOf(CACHE_KEY_SEPARATOR);
                    if (lastSeparator > 0) {
                        String tf = withoutPrefix.substring(lastSeparator + 1);
                        byTimeframe.merge(tf, 1, Integer::sum);
                    }
                }
            }
            stats.put("byTimeframe", byTimeframe);

        } catch (Exception e) {
            log.warn("[TECH_SVC] Error getting cache stats: {}", e.getMessage());
            stats.put("error", e.getMessage());
        }

        return stats;
    }

    /**
     * Clear cache for a specific scrip.
     */
    public void clearCache(String scripCode) {
        try {
            String indicatorPattern = INDICATOR_KEY_PREFIX + scripCode + CACHE_KEY_SEPARATOR + "*";
            String historyPattern = HISTORY_KEY_PREFIX + scripCode + CACHE_KEY_SEPARATOR + "*";

            Set<String> indicatorKeys = redisTemplate.keys(indicatorPattern);
            Set<String> historyKeys = redisTemplate.keys(historyPattern);

            int deleted = 0;
            if (indicatorKeys != null && !indicatorKeys.isEmpty()) {
                deleted += redisTemplate.delete(indicatorKeys);
            }
            if (historyKeys != null && !historyKeys.isEmpty()) {
                deleted += redisTemplate.delete(historyKeys);
            }

            log.info("[TECH_SVC] Cleared cache for scrip {}: {} keys deleted", scripCode, deleted);
        } catch (Exception e) {
            log.error("[TECH_SVC] Error clearing cache for scrip {}: {}", scripCode, e.getMessage());
        }
    }

    /**
     * Clear all caches.
     */
    public void clearAllCaches() {
        try {
            Set<String> indicatorKeys = redisTemplate.keys(INDICATOR_KEY_PREFIX + "*");
            Set<String> historyKeys = redisTemplate.keys(HISTORY_KEY_PREFIX + "*");

            int deleted = 0;
            if (indicatorKeys != null && !indicatorKeys.isEmpty()) {
                deleted += redisTemplate.delete(indicatorKeys);
            }
            if (historyKeys != null && !historyKeys.isEmpty()) {
                deleted += redisTemplate.delete(historyKeys);
            }

            log.info("[TECH_SVC] Cleared all indicator caches: {} keys deleted", deleted);
        } catch (Exception e) {
            log.error("[TECH_SVC] Error clearing all caches: {}", e.getMessage());
        }
    }

    private String buildIndicatorKey(String scripCode, String timeframe) {
        return INDICATOR_KEY_PREFIX + scripCode + CACHE_KEY_SEPARATOR + timeframe;
    }

    private String buildHistoryKey(String scripCode, String timeframe) {
        return HISTORY_KEY_PREFIX + scripCode + CACHE_KEY_SEPARATOR + timeframe;
    }
}
