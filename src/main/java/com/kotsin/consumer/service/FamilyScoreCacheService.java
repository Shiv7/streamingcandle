package com.kotsin.consumer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.score.model.FamilyScore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.*;

/**
 * Service for caching FamilyScore (MTIS) to Redis.
 *
 * CRITICAL: This allows dashboard to survive restarts.
 * Dashboard reads from Redis on startup instead of waiting for Kafka messages.
 *
 * Redis Key Pattern:
 * - family:score:{scripCode} - Latest score (no timeframe suffix, single score per family)
 * - family:score:history:{scripCode} - Historical scores (last 50)
 */
@Slf4j
@Service
public class FamilyScoreCacheService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    // Redis key prefixes
    private static final String SCORE_KEY_PREFIX = "family:score:";
    private static final String HISTORY_KEY_PREFIX = "family:score:history:";

    // Cache settings
    private static final int MAX_HISTORY_SIZE = 50;
    private static final Duration CACHE_TTL = Duration.ofHours(24); // Scores expire after 24 hours

    public FamilyScoreCacheService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void init() {
        log.info("[FAMILY_SCORE_CACHE] FamilyScoreCacheService initialized with Redis-backed persistent cache (TTL={}h)",
                CACHE_TTL.toHours());
    }

    /**
     * Save FamilyScore to Redis.
     * Called after Kafka send to ensure dashboard can read on restart.
     *
     * @param score FamilyScore to cache
     */
    public void cacheScore(FamilyScore score) {
        if (score == null || score.getFamilyId() == null) {
            return;
        }

        String familyId = score.getFamilyId();

        try {
            String json = objectMapper.writeValueAsString(score);
            String scoreKey = SCORE_KEY_PREFIX + familyId;
            String historyKey = HISTORY_KEY_PREFIX + familyId;

            // Store latest score with TTL
            redisTemplate.opsForValue().set(scoreKey, json, CACHE_TTL);

            // Add to history list (push to front, newest first)
            redisTemplate.opsForList().leftPush(historyKey, json);

            // Trim history to max size
            redisTemplate.opsForList().trim(historyKey, 0, MAX_HISTORY_SIZE - 1);

            // Set TTL on history key
            redisTemplate.expire(historyKey, CACHE_TTL);

            log.debug("[FAMILY_SCORE_CACHE] Cached score for {}: MTIS={} label={}",
                    familyId, score.getMtis(), score.getMtisLabel());

        } catch (JsonProcessingException e) {
            log.error("[FAMILY_SCORE_CACHE] Error serializing score for {}: {}", familyId, e.getMessage());
        } catch (Exception e) {
            log.error("[FAMILY_SCORE_CACHE] Error storing score in Redis for {}: {}", familyId, e.getMessage());
        }
    }

    /**
     * Get latest FamilyScore from Redis.
     *
     * @param familyId Family ID (scripCode)
     * @return FamilyScore or null if not available
     */
    public FamilyScore getScore(String familyId) {
        String redisKey = SCORE_KEY_PREFIX + familyId;
        try {
            String json = redisTemplate.opsForValue().get(redisKey);
            if (json == null || json.isEmpty()) {
                return null;
            }
            return objectMapper.readValue(json, FamilyScore.class);
        } catch (Exception e) {
            log.warn("[FAMILY_SCORE_CACHE] Error reading score from Redis for {}: {}", familyId, e.getMessage());
            return null;
        }
    }

    /**
     * Get all cached FamilyScores.
     *
     * @return Map of familyId -> FamilyScore
     */
    public Map<String, FamilyScore> getAllScores() {
        Map<String, FamilyScore> scores = new HashMap<>();

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
                            FamilyScore score = objectMapper.readValue(json, FamilyScore.class);
                            scores.put(score.getFamilyId(), score);
                        } catch (JsonProcessingException e) {
                            log.warn("[FAMILY_SCORE_CACHE] Error parsing score: {}", e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("[FAMILY_SCORE_CACHE] Error getting all scores: {}", e.getMessage());
        }

        return scores;
    }

    /**
     * Get score history for a family.
     *
     * @param familyId Family ID
     * @param limit Maximum number of entries
     * @return List of FamilyScore (chronologically ordered, oldest first)
     */
    public List<FamilyScore> getScoreHistory(String familyId, int limit) {
        String historyKey = HISTORY_KEY_PREFIX + familyId;
        try {
            List<String> jsonList = redisTemplate.opsForList().range(historyKey, 0, limit - 1);
            if (jsonList == null || jsonList.isEmpty()) {
                return Collections.emptyList();
            }

            List<FamilyScore> history = new ArrayList<>();
            for (String json : jsonList) {
                try {
                    history.add(objectMapper.readValue(json, FamilyScore.class));
                } catch (JsonProcessingException e) {
                    log.warn("[FAMILY_SCORE_CACHE] Error parsing history entry: {}", e.getMessage());
                }
            }

            // Reverse to get chronological order
            Collections.reverse(history);
            return history;
        } catch (Exception e) {
            log.warn("[FAMILY_SCORE_CACHE] Error reading history from Redis for {}: {}", familyId, e.getMessage());
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
            stats.put("keyPattern", SCORE_KEY_PREFIX + "{familyId}");

        } catch (Exception e) {
            log.warn("[FAMILY_SCORE_CACHE] Error getting cache stats: {}", e.getMessage());
            stats.put("error", e.getMessage());
        }

        return stats;
    }

    /**
     * Clear cache for a specific family.
     */
    public void clearCache(String familyId) {
        try {
            String scoreKey = SCORE_KEY_PREFIX + familyId;
            String historyKey = HISTORY_KEY_PREFIX + familyId;

            redisTemplate.delete(scoreKey);
            redisTemplate.delete(historyKey);

            log.info("[FAMILY_SCORE_CACHE] Cleared cache for family {}", familyId);
        } catch (Exception e) {
            log.error("[FAMILY_SCORE_CACHE] Error clearing cache for family {}: {}", familyId, e.getMessage());
        }
    }
}
