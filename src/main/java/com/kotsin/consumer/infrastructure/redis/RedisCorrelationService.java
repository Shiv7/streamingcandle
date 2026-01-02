package com.kotsin.consumer.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * RedisCorrelationService - Price-based correlation calculation using Redis
 *
 * MASTER ARCHITECTURE - Security Regime Micro-Leader Override:
 * Calculates correlation between security and index based on price returns
 *
 * Formula: Pearson Correlation Coefficient
 * ρ(X,Y) = Σ((xi - x̄)(yi - ȳ)) / sqrt(Σ(xi - x̄)² × Σ(yi - ȳ)²)
 *
 * Returns value between -1 and +1:
 * - ρ > 0.75 → highly correlated (moves with index)
 * - ρ < 0.30 → low correlation (potential micro-leader)
 *
 * Data Structure:
 * - ZADD returns:{scripCode} {timestamp} {price_return}
 * - Stores 1-year rolling window of price returns
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisCorrelationService {

    private final RedisTemplate<String, Double> redisTemplate;

    // Key prefix
    private static final String PRICE_RETURNS_PREFIX = "returns:";

    // 1 year in milliseconds
    private static final long ONE_YEAR_MS = Duration.ofDays(365).toMillis();

    // Minimum data points for reliable correlation
    private static final int MIN_DATA_POINTS = 20;

    // Default lookback for correlation calculation (20 bars = ~1 trading day on 30m)
    private static final int DEFAULT_LOOKBACK = 20;

    private ZSetOperations<String, Double> zSetOps;

    @PostConstruct
    public void init() {
        this.zSetOps = redisTemplate.opsForZSet();
        log.info("RedisCorrelationService initialized with 1-year rolling window");
    }

    /**
     * Store price return for a scripCode
     *
     * @param scripCode Security identifier (e.g., "RELIANCE", "INFY")
     * @param priceReturn Price return: (Price_t - Price_t-1) / Price_t-1
     * @param timestamp Candle timestamp
     */
    public void storePriceReturn(String scripCode, double priceReturn, long timestamp) {
        String key = PRICE_RETURNS_PREFIX + scripCode;

        // Store return with timestamp as score
        zSetOps.add(key, priceReturn, timestamp);

        log.debug("Stored price return for {}: return={}, timestamp={}",
            scripCode, priceReturn, timestamp);

        // Cleanup old data beyond 1 year
        cleanupOldData(key, timestamp - ONE_YEAR_MS);
    }

    /**
     * Calculate correlation between security and index
     *
     * @param securityCode Security identifier (e.g., "RELIANCE")
     * @param indexCode Index identifier (e.g., "999920000" for NIFTY50)
     * @return Correlation coefficient [-1.0, +1.0], or 0.0 if insufficient data
     */
    public double calculateCorrelation(String securityCode, String indexCode) {
        return calculateCorrelation(securityCode, indexCode, DEFAULT_LOOKBACK);
    }

    /**
     * Calculate correlation between security and index with custom lookback
     *
     * @param securityCode Security identifier
     * @param indexCode Index identifier
     * @param lookback Number of recent data points to use
     * @return Correlation coefficient [-1.0, +1.0], or 0.0 if insufficient data
     */
    public double calculateCorrelation(String securityCode, String indexCode, int lookback) {
        String securityKey = PRICE_RETURNS_PREFIX + securityCode;
        String indexKey = PRICE_RETURNS_PREFIX + indexCode;

        // Get recent returns for both security and index
        Set<ZSetOperations.TypedTuple<Double>> securityReturns =
            zSetOps.reverseRangeWithScores(securityKey, 0, lookback - 1);
        Set<ZSetOperations.TypedTuple<Double>> indexReturns =
            zSetOps.reverseRangeWithScores(indexKey, 0, lookback - 1);

        if (securityReturns == null || indexReturns == null ||
            securityReturns.size() < MIN_DATA_POINTS ||
            indexReturns.size() < MIN_DATA_POINTS) {

            log.debug("Insufficient data for correlation: security={} points, index={} points (need {})",
                securityReturns != null ? securityReturns.size() : 0,
                indexReturns != null ? indexReturns.size() : 0,
                MIN_DATA_POINTS);

            return 0.0;
        }

        // Convert to aligned lists (match timestamps)
        List<Double> xValues = new ArrayList<>();
        List<Double> yValues = new ArrayList<>();

        for (ZSetOperations.TypedTuple<Double> secTuple : securityReturns) {
            double secTimestamp = secTuple.getScore();
            Double secReturn = secTuple.getValue();

            // Find corresponding index return at same timestamp (within tolerance)
            for (ZSetOperations.TypedTuple<Double> idxTuple : indexReturns) {
                double idxTimestamp = idxTuple.getScore();
                Double idxReturn = idxTuple.getValue();

                // Match timestamps within 1 minute tolerance
                if (Math.abs(secTimestamp - idxTimestamp) < 60000) {
                    if (secReturn != null && idxReturn != null) {
                        xValues.add(secReturn);
                        yValues.add(idxReturn);
                    }
                    break;
                }
            }
        }

        if (xValues.size() < MIN_DATA_POINTS) {
            log.debug("Insufficient aligned data points: {} (need {})",
                xValues.size(), MIN_DATA_POINTS);
            return 0.0;
        }

        // Calculate Pearson correlation
        double correlation = calculatePearsonCorrelation(xValues, yValues);

        log.debug("Calculated correlation between {} and {}: {} (data points: {})",
            securityCode, indexCode, String.format("%.3f", correlation), xValues.size());

        return correlation;
    }

    /**
     * Calculate Pearson correlation coefficient
     *
     * Formula: ρ(X,Y) = Σ((xi - x̄)(yi - ȳ)) / sqrt(Σ(xi - x̄)² × Σ(yi - ȳ)²)
     *
     * @param x First variable (security returns)
     * @param y Second variable (index returns)
     * @return Correlation coefficient [-1.0, +1.0]
     */
    private double calculatePearsonCorrelation(List<Double> x, List<Double> y) {
        if (x.size() != y.size() || x.isEmpty()) {
            log.warn("Invalid input for correlation: x.size={}, y.size={}",
                x.size(), y.size());
            return 0.0;
        }

        int n = x.size();

        // Calculate means
        double xMean = x.stream().mapToDouble(d -> d).average().orElse(0.0);
        double yMean = y.stream().mapToDouble(d -> d).average().orElse(0.0);

        log.debug("Correlation calculation: n={}, xMean={}, yMean={}",
            n, String.format("%.6f", xMean), String.format("%.6f", yMean));

        // Calculate covariance and standard deviations
        double covariance = 0.0;
        double xVariance = 0.0;
        double yVariance = 0.0;

        for (int i = 0; i < n; i++) {
            double xDiff = x.get(i) - xMean;
            double yDiff = y.get(i) - yMean;

            covariance += xDiff * yDiff;
            xVariance += xDiff * xDiff;
            yVariance += yDiff * yDiff;
        }

        // Avoid division by zero
        if (xVariance == 0.0 || yVariance == 0.0) {
            log.debug("Zero variance detected: xVar={}, yVar={} - returning 0",
                xVariance, yVariance);
            return 0.0;
        }

        // Pearson correlation coefficient
        double correlation = covariance / Math.sqrt(xVariance * yVariance);

        // Clamp to [-1, 1] to handle floating point errors
        correlation = Math.max(-1.0, Math.min(1.0, correlation));

        log.debug("Correlation components: covariance={}, xStdDev={}, yStdDev={}, correlation={}",
            String.format("%.6f", covariance),
            String.format("%.6f", Math.sqrt(xVariance)),
            String.format("%.6f", Math.sqrt(yVariance)),
            String.format("%.3f", correlation));

        return correlation;
    }

    /**
     * Remove data older than cutoff timestamp
     */
    private void cleanupOldData(String key, long cutoffTimestamp) {
        Long removed = zSetOps.removeRangeByScore(key, Double.NEGATIVE_INFINITY, cutoffTimestamp);
        if (removed != null && removed > 0) {
            log.debug("Cleaned up {} old price returns from {}", removed, key);
        }
    }

    /**
     * Get data point count for a scripCode
     */
    public long getDataPointCount(String scripCode) {
        String key = PRICE_RETURNS_PREFIX + scripCode;
        Long size = zSetOps.size(key);
        return size != null ? size : 0;
    }

    /**
     * Check if correlation data is available
     */
    public boolean hasCorrelationData(String scripCode) {
        return getDataPointCount(scripCode) >= MIN_DATA_POINTS;
    }
}
