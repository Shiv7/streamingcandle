package com.kotsin.consumer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.quant.model.QuantScore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.*;

/**
 * Service for caching QuantScore to Redis.
 *
 * CRITICAL: This allows dashboard to survive restarts.
 * Dashboard reads from Redis on startup instead of waiting for Kafka messages.
 *
 * Redis Key Pattern:
 * - quant:score:{familyId}:{timeframe} - Latest score per family/timeframe
 * - quant:score:history:{familyId}:{timeframe} - Historical scores (last 50)
 */
@Slf4j
@Service
public class QuantScoreCacheService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    // Redis key prefixes
    private static final String SCORE_KEY_PREFIX = "quant:score:";
    private static final String HISTORY_KEY_PREFIX = "quant:score:history:";

    // Cache settings
    private static final int MAX_HISTORY_SIZE = 50;
    private static final Duration CACHE_TTL = Duration.ofHours(24); // Scores expire after 24 hours

    public QuantScoreCacheService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void init() {
        log.info("[QUANT_SCORE_CACHE] QuantScoreCacheService initialized with Redis-backed persistent cache (TTL={}h)",
                CACHE_TTL.toHours());
    }

    /**
     * Save QuantScore to Redis.
     * Called after Kafka send to ensure dashboard can read on restart.
     *
     * @param score QuantScore to cache
     */
    public void cacheScore(QuantScore score) {
        if (score == null || score.getFamilyId() == null) {
            return;
        }

        String familyId = score.getFamilyId();
        String timeframe = score.getTimeframe() != null ? score.getTimeframe() : "1m";
        String cacheKey = familyId + ":" + timeframe;

        try {
            String json = objectMapper.writeValueAsString(score);
            String scoreKey = SCORE_KEY_PREFIX + cacheKey;
            String historyKey = HISTORY_KEY_PREFIX + cacheKey;

            // Store latest score with TTL
            redisTemplate.opsForValue().set(scoreKey, json, CACHE_TTL);

            // Add to history list (push to front, newest first)
            redisTemplate.opsForList().leftPush(historyKey, json);

            // Trim history to max size
            redisTemplate.opsForList().trim(historyKey, 0, MAX_HISTORY_SIZE - 1);

            // Set TTL on history key
            redisTemplate.expire(historyKey, CACHE_TTL);

            log.debug("[QUANT_SCORE_CACHE] Cached score for {} ({}): score={} label={}",
                    familyId, timeframe, String.format("%.1f", score.getQuantScore()), score.getQuantLabel());

        } catch (JsonProcessingException e) {
            log.error("[QUANT_SCORE_CACHE] Error serializing score for {}: {}", cacheKey, e.getMessage());
        } catch (Exception e) {
            log.error("[QUANT_SCORE_CACHE] Error storing score in Redis for {}: {}", cacheKey, e.getMessage());
        }
    }

    /**
     * Get latest QuantScore from Redis.
     *
     * @param familyId Family ID (scripCode)
     * @param timeframe Timeframe (1m, 5m, etc.)
     * @return QuantScore or null if not available
     */
    public QuantScore getScore(String familyId, String timeframe) {
        String cacheKey = familyId + ":" + (timeframe != null ? timeframe : "1m");
        String redisKey = SCORE_KEY_PREFIX + cacheKey;
        try {
            String json = redisTemplate.opsForValue().get(redisKey);
            if (json == null || json.isEmpty()) {
                return null;
            }
            return objectMapper.readValue(json, QuantScore.class);
        } catch (Exception e) {
            log.warn("[QUANT_SCORE_CACHE] Error reading score from Redis for {}: {}", cacheKey, e.getMessage());
            return null;
        }
    }

    /**
     * Get all cached QuantScores (all families, all timeframes).
     *
     * @return Map of cacheKey (familyId:timeframe) -> QuantScore
     */
    public Map<String, QuantScore> getAllScores() {
        Map<String, QuantScore> scores = new HashMap<>();

        try {
            Set<String> keys = redisTemplate.keys(SCORE_KEY_PREFIX + "*");
            if (keys == null || keys.isEmpty()) {
                return scores;
            }

            // Filter out history keys
            List<String> scoreKeys = new ArrayList<>();
            for (String key : keys) {
                if (!key.contains(":history:")) {
                    scoreKeys.add(key);
                }
            }

            // Batch fetch
            List<String> values = redisTemplate.opsForValue().multiGet(scoreKeys);
            if (values != null) {
                for (int i = 0; i < scoreKeys.size(); i++) {
                    String json = values.get(i);
                    if (json != null && !json.isEmpty()) {
                        try {
                            QuantScore score = objectMapper.readValue(json, QuantScore.class);
                            String familyId = score.getFamilyId();
                            String timeframe = score.getTimeframe() != null ? score.getTimeframe() : "1m";
                            scores.put(familyId + ":" + timeframe, score);
                        } catch (JsonProcessingException e) {
                            log.warn("[QUANT_SCORE_CACHE] Error parsing score: {}", e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("[QUANT_SCORE_CACHE] Error getting all scores: {}", e.getMessage());
        }

        return scores;
    }

    /**
     * Get score history for a family.
     *
     * @param familyId Family ID
     * @param timeframe Timeframe
     * @param limit Maximum number of entries
     * @return List of QuantScore (chronologically ordered, oldest first)
     */
    public List<QuantScore> getScoreHistory(String familyId, String timeframe, int limit) {
        String cacheKey = familyId + ":" + (timeframe != null ? timeframe : "1m");
        String historyKey = HISTORY_KEY_PREFIX + cacheKey;
        try {
            List<String> jsonList = redisTemplate.opsForList().range(historyKey, 0, limit - 1);
            if (jsonList == null || jsonList.isEmpty()) {
                return Collections.emptyList();
            }

            List<QuantScore> history = new ArrayList<>();
            for (String json : jsonList) {
                try {
                    history.add(objectMapper.readValue(json, QuantScore.class));
                } catch (JsonProcessingException e) {
                    log.warn("[QUANT_SCORE_CACHE] Error parsing history entry: {}", e.getMessage());
                }
            }

            // Reverse to get chronological order
            Collections.reverse(history);
            return history;
        } catch (Exception e) {
            log.warn("[QUANT_SCORE_CACHE] Error reading history from Redis for {}: {}", cacheKey, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get cache statistics.
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();

        try {
            Set<String> scoreKeys = redisTemplate.keys(SCORE_KEY_PREFIX + "*");
            int scoreCount = 0;
            int historyCount = 0;

            if (scoreKeys != null) {
                for (String key : scoreKeys) {
                    if (key.contains(":history:")) {
                        historyCount++;
                    } else {
                        scoreCount++;
                    }
                }
            }

            stats.put("scoreCacheSize", scoreCount);
            stats.put("historyCacheSize", historyCount);
            stats.put("cacheType", "Redis");
            stats.put("ttlHours", CACHE_TTL.toHours());
            stats.put("keyPattern", SCORE_KEY_PREFIX + "{familyId}:{timeframe}");

        } catch (Exception e) {
            log.warn("[QUANT_SCORE_CACHE] Error getting cache stats: {}", e.getMessage());
            stats.put("error", e.getMessage());
        }

        return stats;
    }

    /**
     * Clear cache for a specific family and timeframe.
     */
    public void clearCache(String familyId, String timeframe) {
        try {
            String cacheKey = familyId + ":" + (timeframe != null ? timeframe : "1m");
            String scoreKey = SCORE_KEY_PREFIX + cacheKey;
            String historyKey = HISTORY_KEY_PREFIX + cacheKey;

            redisTemplate.delete(scoreKey);
            redisTemplate.delete(historyKey);

            log.info("[QUANT_SCORE_CACHE] Cleared cache for {}", cacheKey);
        } catch (Exception e) {
            log.error("[QUANT_SCORE_CACHE] Error clearing cache for {}:{}: {}", familyId, timeframe, e.getMessage());
        }
    }

    /**
     * Clear all cache for a family (all timeframes).
     */
    public void clearAllCacheForFamily(String familyId) {
        try {
            Set<String> keys = redisTemplate.keys(SCORE_KEY_PREFIX + familyId + ":*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }

            Set<String> historyKeys = redisTemplate.keys(HISTORY_KEY_PREFIX + familyId + ":*");
            if (historyKeys != null && !historyKeys.isEmpty()) {
                redisTemplate.delete(historyKeys);
            }

            log.info("[QUANT_SCORE_CACHE] Cleared all cache for family {}", familyId);
        } catch (Exception e) {
            log.error("[QUANT_SCORE_CACHE] Error clearing cache for family {}: {}", familyId, e.getMessage());
        }
    }
}
