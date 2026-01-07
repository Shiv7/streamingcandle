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
     * Calculate percentile rank using Redis sorted set
     * 
     * Algorithm:
     * 1. Get all values from sorted set
     * 2. Count how many values are less than current value
     * 3. Percentile = (count / total) * 100
     * 
     * @param key Redis key
     * @param currentValue Value to rank
     * @return Percentile [0, 100] or -1 if insufficient data
     */
    private double calculatePercentile(String key, double currentValue) {
        Long totalCount = zSetOps.zCard(key);
        
        if (totalCount == null || totalCount < 30) {
            // Need minimum 30 data points for meaningful percentile
            log.trace("Insufficient data for percentile calculation: key={}, count={}", key, totalCount);
            return -1.0;
        }
        
        // Get all values to count how many are less than current
        Set<Double> allValues = zSetOps.range(key, 0, -1);
        if (allValues == null || allValues.isEmpty()) {
            return -1.0;
        }
        
        long countLessThan = allValues.stream()
            .filter(v -> v < currentValue)
            .count();
        
        return (countLessThan / (double) totalCount) * 100.0;
    }
    
    /**
     * Alternative percentile calculation using ZRANK for approximate percentile
     * (More efficient for large datasets)
     */
    public double calculateApproximatePercentile(String key, double currentValue) {
        // First, add the current value temporarily
        long now = Instant.now().toEpochMilli();
        zSetOps.add(key, currentValue, now);
        
        // Get rank and total
        Long rank = zSetOps.rank(key, currentValue);
        Long total = zSetOps.zCard(key);
        
        // Remove the temporary value
        zSetOps.remove(key, currentValue);
        
        if (rank == null || total == null || total < 30) {
            return -1.0;
        }
        
        return (rank / (double) total) * 100.0;
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
