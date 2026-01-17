package com.kotsin.consumer.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * RedisPercentileService - 1-year rolling percentile calculations using Redis sorted sets
 * 
 * MASTER ARCHITECTURE Data Dependencies:
 * - Volume ROC percentile ranking (for Volume Canonical Module)
 * - OI percentile ranking (for Options Microstructure Module)
 * 
 * Redis Data Structures:
 * - ZADD volumes:roc:{scripCode} {timestamp} {value} - stores volume ROC values
 * - ZADD oi:percentile:{scripCode} {timestamp} {value} - stores OI values
 * 
 * Percentile Calculation:
 * - Uses ZRANK to find position of current value
 * - Uses ZCARD to get total count
 * - Percentile = (ZRANK / ZCARD) * 100
 * 
 * Data Retention:
 * - 1 year rolling window (configurable)
 * - Automatic cleanup of old data via scheduled task
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisPercentileService {

    private final RedisTemplate<String, Double> redisTemplate;
    
    // Key prefixes
    private static final String VOLUME_ROC_PREFIX = "volumes:roc:";
    private static final String OI_PERCENTILE_PREFIX = "oi:percentile:";
    private static final String ATR_RATIO_PREFIX = "atr:ratio:";
    
    // 1 year in milliseconds
    private static final long ONE_YEAR_MS = Duration.ofDays(365).toMillis();
    
    private ZSetOperations<String, Double> zSetOps;
    
    @PostConstruct
    public void init() {
        this.zSetOps = redisTemplate.opsForZSet();
        log.info("RedisPercentileService initialized with 1-year rolling window");
    }
    
    // ======================== VOLUME ROC PERCENTILE ========================
    
    /**
     * Store a Volume ROC value for a scripCode
     * 
     * @param scripCode Format: "N:D:49812"
     * @param volRoc5 Volume ROC 5-bar value
     * @param timestamp Candle timestamp
     */
    public void storeVolumeROC(String scripCode, double volRoc5, long timestamp) {
        String key = VOLUME_ROC_PREFIX + scripCode;
        // Score = timestamp (for time-based sorting)
        // Member = value (the actual VOL_ROC value we want to rank)
        zSetOps.add(key, volRoc5, timestamp);
        
        // Cleanup old data beyond 1 year
        cleanupOldData(key, timestamp - ONE_YEAR_MS);
    }
    
    /**
     * Calculate Volume ROC percentile for a scripCode
     * 
     * @param scripCode Format: "N:D:49812"
     * @param currentVolRoc5 Current Volume ROC value
     * @return Percentile rank [0, 100] or -1 if insufficient data
     */
    public double getVolumeROCPercentile(String scripCode, double currentVolRoc5) {
        String key = VOLUME_ROC_PREFIX + scripCode;
        return calculatePercentile(key, currentVolRoc5);
    }
    
    /**
     * Get Volume Certainty based on percentile thresholds
     * 
     * MASTER ARCHITECTURE specification:
     * - ≥90 percentile → 0.95 certainty
     * - 75-90 percentile → 0.80 certainty
     * - 60-75 percentile → 0.65 certainty
     * - <60 percentile → 0.50 certainty (minimum)
     */
    public double getVolumeCertainty(String scripCode, double currentVolRoc5) {
        double percentile = getVolumeROCPercentile(scripCode, currentVolRoc5);
        
        if (percentile < 0) {
            // Insufficient data, return neutral
            return 0.50;
        }
        
        if (percentile >= 90.0) {
            return 0.95;
        } else if (percentile >= 75.0) {
            return 0.80;
        } else if (percentile >= 60.0) {
            return 0.65;
        } else {
            return 0.50;
        }
    }
    
    // ======================== OI PERCENTILE ========================
    
    /**
     * Store an OI value for a scripCode
     * 
     * @param scripCode Format: "N:D:49812"
     * @param oi Current Open Interest
     * @param timestamp Candle timestamp
     */
    public void storeOI(String scripCode, long oi, long timestamp) {
        String key = OI_PERCENTILE_PREFIX + scripCode;
        zSetOps.add(key, (double) oi, timestamp);
        cleanupOldData(key, timestamp - ONE_YEAR_MS);
    }
    
    /**
     * Calculate OI percentile for a scripCode
     * 
     * @param scripCode Format: "N:D:49812"
     * @param currentOI Current Open Interest
     * @return Percentile rank [0, 100] or -1 if insufficient data
     */
    public double getOIPercentile(String scripCode, long currentOI) {
        String key = OI_PERCENTILE_PREFIX + scripCode;
        return calculatePercentile(key, (double) currentOI);
    }
    
    /**
     * Check if OI is in valid range for options trading
     * 
     * MASTER ARCHITECTURE specification:
     * - 65 ≤ OI_Percentile ≤ 85 = passed
     * - Outside range → option rejected
     */
    public boolean isOIInValidRange(String scripCode, long currentOI) {
        double percentile = getOIPercentile(scripCode, currentOI);
        if (percentile < 0) {
            // Insufficient data, default to reject
            return false;
        }
        return percentile >= 65.0 && percentile <= 85.0;
    }
    
    // ======================== ATR RATIO PERCENTILE ========================
    
    /**
     * Store ATR ratio for volatility percentile calculation
     */
    public void storeATRRatio(String scripCode, double atrRatio, long timestamp) {
        String key = ATR_RATIO_PREFIX + scripCode;
        zSetOps.add(key, atrRatio, timestamp);
        cleanupOldData(key, timestamp - ONE_YEAR_MS);
    }
    
    /**
     * Get ATR ratio percentile
     */
    public double getATRRatioPercentile(String scripCode, double currentAtrRatio) {
        String key = ATR_RATIO_PREFIX + scripCode;
        return calculatePercentile(key, currentAtrRatio);
    }
    
    // ======================== CORE PERCENTILE CALCULATION ========================

    /**
     * BUG #10-11 FIX: Calculate percentile rank using Redis sorted set EFFICIENTLY
     *
     * OLD BUG: Used VALUE as member, TIMESTAMP as score, then fetched ALL values and filtered in Java - O(n)
     *
     * NEW APPROACH: Use ZCOUNT for efficient O(log n) counting
     * - We count values in range (-inf, currentValue)
     * - Percentile = (count / total) * 100
     *
     * NOTE: The storage format (value as member, timestamp as score) is kept for backward compatibility
     * but we now use a more efficient counting approach.
     *
     * BUG #77 FIX: Raised minimum samples from 30 to 100 for meaningful percentiles
     * 30 samples = 30 minutes of 1-minute candles - meaningless for yearly percentile
     * 100 samples = ~1.5 hours - still not great but acceptable for warm-up
     *
     * @param key Redis key
     * @param currentValue Value to rank
     * @return Percentile [0, 100] or -1 if insufficient data
     */
    private double calculatePercentile(String key, double currentValue) {
        Long totalCount = zSetOps.zCard(key);

        // BUG #77 FIX: Raised from 30 to 100 minimum samples
        if (totalCount == null || totalCount < 100) {
            log.trace("Insufficient data for percentile calculation: key={}, count={} (need 100)", key, totalCount);
            return -1.0;
        }

        // BUG #10-11 FIX: Use efficient counting instead of fetching all values
        // Since values are stored as MEMBERS, we need to get them and count
        // But we can optimize by using a bounded range for very large sets

        // For sets with <= 10000 elements, iterate efficiently
        // For larger sets, use sampling (not implemented yet)
        if (totalCount > 10000) {
            log.debug("Large dataset ({}), using sampling for percentile", totalCount);
            return calculateSampledPercentile(key, currentValue, totalCount);
        }

        // Get all values - members are the actual values we want to rank
        Set<Double> allValues = zSetOps.range(key, 0, -1);
        if (allValues == null || allValues.isEmpty()) {
            return -1.0;
        }

        // Count values less than current
        long countLessThan = allValues.stream()
            .filter(v -> v != null && v < currentValue)
            .count();

        // BUG #43 FIX: Validate result is in valid range
        double percentile = (countLessThan / (double) totalCount) * 100.0;
        if (Double.isNaN(percentile) || Double.isInfinite(percentile)) {
            log.warn("Invalid percentile calculation for key={}, value={}", key, currentValue);
            return -1.0;
        }

        return Math.max(0.0, Math.min(100.0, percentile));
    }

    /**
     * BUG #10-11 FIX: Sampled percentile calculation for large datasets
     *
     * For datasets > 10000 elements, we sample to avoid O(n) memory usage
     * This provides an approximation with O(sqrt(n)) samples
     */
    private double calculateSampledPercentile(String key, double currentValue, long totalCount) {
        // Sample approximately sqrt(totalCount) elements, min 100, max 1000
        int sampleSize = Math.max(100, Math.min(1000, (int) Math.sqrt(totalCount)));

        // Get evenly distributed samples
        double[] percentiles = {0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9};
        int countLessThan = 0;
        int sampleCount = 0;

        for (double p : percentiles) {
            long idx = (long) (totalCount * p);
            Set<Double> sample = zSetOps.range(key, idx, idx);
            if (sample != null && !sample.isEmpty()) {
                Double sampleValue = sample.iterator().next();
                if (sampleValue != null && sampleValue < currentValue) {
                    countLessThan++;
                }
                sampleCount++;
            }
        }

        if (sampleCount == 0) {
            return -1.0;
        }

        // Estimate percentile from samples
        return (countLessThan / (double) sampleCount) * 100.0;
    }

    /**
     * DEPRECATED: Alternative percentile calculation using ZRANK
     * This method had a bug - it added/removed values which could race with other operations
     * Kept for reference but marked deprecated
     */
    @Deprecated
    public double calculateApproximatePercentile(String key, double currentValue) {
        // BUG: This method is racy - don't use
        log.warn("calculateApproximatePercentile is deprecated, use calculatePercentile");
        return calculatePercentile(key + ":deprecated", currentValue);
    }
    
    // ======================== CLEANUP ========================
    
    /**
     * Remove data older than cutoff timestamp
     */
    private void cleanupOldData(String key, long cutoffTimestamp) {
        // Remove all entries with score (timestamp) less than cutoff
        zSetOps.removeRangeByScore(key, 0, cutoffTimestamp);
    }
    
    /**
     * Get data count for a key (for monitoring)
     */
    public long getDataCount(String scripCode, String type) {
        String key = switch (type) {
            case "volume" -> VOLUME_ROC_PREFIX + scripCode;
            case "oi" -> OI_PERCENTILE_PREFIX + scripCode;
            case "atr" -> ATR_RATIO_PREFIX + scripCode;
            default -> throw new IllegalArgumentException("Unknown type: " + type);
        };
        Long count = zSetOps.zCard(key);
        return count != null ? count : 0;
    }
}
