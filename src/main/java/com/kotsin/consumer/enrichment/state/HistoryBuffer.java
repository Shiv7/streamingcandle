package com.kotsin.consumer.enrichment.state;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.enrichment.calculator.StatisticsCalculator;
import com.kotsin.consumer.enrichment.model.MetricContext;
import com.kotsin.consumer.enrichment.model.MetricContext.FlipType;
import com.kotsin.consumer.enrichment.model.MetricContext.MetricRegime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * HistoryBuffer - Rolling window history management for SMTIS Phase 1
 *
 * Stores last N values of each metric per family/timeframe combination.
 * Uses Redis Lists for efficient append/trim operations.
 *
 * Redis Key Format:
 * - smtis:history:{familyId}:{timeframe}:{metric} -> List of last 20 values
 * - smtis:regime:{familyId}:{timeframe}:{metric} -> Current regime state
 * - smtis:prev:{familyId}:{timeframe} -> Previous candle snapshot
 *
 * Features:
 * - Automatic TTL (24h) to prevent stale data buildup
 * - Learning mode detection (< 20 samples)
 * - Regime detection with flip tracking
 * - Thread-safe Redis operations
 *
 * Metrics Tracked:
 * - OFI (Order Flow Imbalance)
 * - volumeDelta
 * - oiChange (Open Interest Change)
 * - oiChangePercent
 * - kyleLambda
 * - vpin
 * - depthImbalance
 * - spreadRatio
 * - buyPressure
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryBuffer {

    private final StringRedisTemplate stringRedisTemplate;
    private final StatisticsCalculator statisticsCalculator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Configuration
    private static final int DEFAULT_BUFFER_SIZE = 20;
    // BUG #21 FIX: Extended TTL from 24h to 7 days (168h)
    // Old: Every morning, all percentiles reset = learning mode = garbage signals
    // New: Keep 7 days of history for stable percentile calculations
    private static final long TTL_HOURS = 168; // 7 days

    // Key prefixes
    private static final String HISTORY_PREFIX = "smtis:history:";
    private static final String REGIME_PREFIX = "smtis:regime:";
    private static final String PREV_PREFIX = "smtis:prev:";

    // Thresholds for regime detection
    private static final double STRONG_PERCENTILE = 80.0;
    private static final double WEAK_PERCENTILE = 40.0;
    private static final int STRONG_CONSECUTIVE = 3;
    private static final int WEAK_CONSECUTIVE = 2;

    @PostConstruct
    public void init() {
        log.info("HistoryBuffer initialized with buffer_size={}, ttl={}h", DEFAULT_BUFFER_SIZE, TTL_HOURS);
    }

    /**
     * Add a value to the history buffer and get full context
     *
     * @param familyId Family ID (e.g., "NATURALGAS")
     * @param timeframe Timeframe (e.g., "1m", "30m")
     * @param metricName Metric name (e.g., "ofi", "volumeDelta")
     * @param value Current value
     * @return MetricContext with statistics and regime detection
     */
    public MetricContext addAndGetContext(String familyId, String timeframe, String metricName, double value) {
        String historyKey = buildHistoryKey(familyId, timeframe, metricName);
        String regimeKey = buildRegimeKey(familyId, timeframe, metricName);

        try {
            // Get existing history
            List<Double> history = getHistory(historyKey);

            // Get previous regime
            MetricRegime previousRegime = getStoredRegime(regimeKey);

            // Add new value
            history.add(value);

            // Trim to buffer size
            while (history.size() > DEFAULT_BUFFER_SIZE) {
                history.remove(0);
            }

            // Store updated history
            storeHistory(historyKey, history);

            // Check if in learning mode
            if (history.size() < DEFAULT_BUFFER_SIZE) {
                return MetricContext.learningMode(metricName, familyId, timeframe, value, history.size());
            }

            // Calculate statistics
            StatisticsCalculator.Statistics stats = statisticsCalculator.calculateAll(value, history);

            // Calculate consecutive count
            int consecutivePositive = statisticsCalculator.calculateConsecutiveCount(history, true);
            int consecutiveNegative = statisticsCalculator.calculateConsecutiveCount(history, false);
            int consecutiveCount = value > 0 ? consecutivePositive : consecutiveNegative;

            // Detect regime
            MetricRegime currentRegime = detectRegime(stats.percentile(), consecutiveCount, value > 0);

            // Store regime
            storeRegime(regimeKey, currentRegime);

            // Detect flip
            FlipType flipType = detectFlip(previousRegime, currentRegime);
            boolean flipDetected = flipType != FlipType.NONE;

            // Calculate flip magnitude if flip detected
            double previousValue = history.size() > 1 ? history.get(history.size() - 2) : value;
            double flipMagnitude = flipDetected ? Math.abs(value - previousValue) : 0;
            double flipZscore = flipDetected ? statisticsCalculator.calculateZScore(flipMagnitude, history) : 0;

            // Build full context
            return MetricContext.builder()
                    .metricName(metricName)
                    .familyId(familyId)
                    .timeframe(timeframe)
                    .currentValue(value)
                    .previousValue(previousValue)
                    .history(new ArrayList<>(history))
                    .historySize(history.size())
                    .mean(stats.mean())
                    .stddev(stats.stddev())
                    .zscore(stats.zscore())
                    .percentile(stats.percentile())
                    .regime(currentRegime)
                    .previousRegime(previousRegime)
                    .consecutiveCount(consecutiveCount)
                    .flipDetected(flipDetected)
                    .flipType(flipType)
                    .flipMagnitude(flipMagnitude)
                    .flipZscore(flipZscore)
                    .inLearningMode(false)
                    .samplesCollected(history.size())
                    .build();

        } catch (Exception e) {
            log.error("Error in addAndGetContext for {}:{}:{}: {}",
                    familyId, timeframe, metricName, e.getMessage());
            return MetricContext.learningMode(metricName, familyId, timeframe, value, 0);
        }
    }

    /**
     * Get context without adding a new value (read-only)
     */
    public MetricContext getContext(String familyId, String timeframe, String metricName) {
        String historyKey = buildHistoryKey(familyId, timeframe, metricName);
        String regimeKey = buildRegimeKey(familyId, timeframe, metricName);

        try {
            List<Double> history = getHistory(historyKey);

            if (history.isEmpty()) {
                return MetricContext.learningMode(metricName, familyId, timeframe, 0, 0);
            }

            double currentValue = history.get(history.size() - 1);
            MetricRegime regime = getStoredRegime(regimeKey);

            if (history.size() < DEFAULT_BUFFER_SIZE) {
                return MetricContext.learningMode(metricName, familyId, timeframe, currentValue, history.size());
            }

            StatisticsCalculator.Statistics stats = statisticsCalculator.calculateAll(currentValue, history);

            return MetricContext.builder()
                    .metricName(metricName)
                    .familyId(familyId)
                    .timeframe(timeframe)
                    .currentValue(currentValue)
                    .history(new ArrayList<>(history))
                    .historySize(history.size())
                    .mean(stats.mean())
                    .stddev(stats.stddev())
                    .zscore(stats.zscore())
                    .percentile(stats.percentile())
                    .regime(regime)
                    .inLearningMode(false)
                    .samplesCollected(history.size())
                    .build();

        } catch (Exception e) {
            log.error("Error in getContext for {}:{}:{}: {}",
                    familyId, timeframe, metricName, e.getMessage());
            return MetricContext.learningMode(metricName, familyId, timeframe, 0, 0);
        }
    }

    /**
     * Check if buffer has sufficient history
     */
    public boolean hasSufficientHistory(String familyId, String timeframe, String metricName) {
        String historyKey = buildHistoryKey(familyId, timeframe, metricName);
        Long size = stringRedisTemplate.opsForList().size(historyKey);
        return size != null && size >= DEFAULT_BUFFER_SIZE;
    }

    /**
     * Get current sample count
     */
    public int getSampleCount(String familyId, String timeframe, String metricName) {
        String historyKey = buildHistoryKey(familyId, timeframe, metricName);
        Long size = stringRedisTemplate.opsForList().size(historyKey);
        return size != null ? size.intValue() : 0;
    }

    /**
     * Clear history for a family/timeframe (useful for testing)
     */
    public void clearHistory(String familyId, String timeframe, String metricName) {
        String historyKey = buildHistoryKey(familyId, timeframe, metricName);
        String regimeKey = buildRegimeKey(familyId, timeframe, metricName);
        stringRedisTemplate.delete(historyKey);
        stringRedisTemplate.delete(regimeKey);
    }

    // ======================== PRIVATE METHODS ========================

    private String buildHistoryKey(String familyId, String timeframe, String metricName) {
        return HISTORY_PREFIX + familyId + ":" + timeframe + ":" + metricName;
    }

    private String buildRegimeKey(String familyId, String timeframe, String metricName) {
        return REGIME_PREFIX + familyId + ":" + timeframe + ":" + metricName;
    }

    private List<Double> getHistory(String key) {
        try {
            List<String> values = stringRedisTemplate.opsForList().range(key, 0, -1);
            if (values == null || values.isEmpty()) {
                return new ArrayList<>();
            }

            List<Double> history = new ArrayList<>();
            for (String v : values) {
                try {
                    history.add(Double.parseDouble(v));
                } catch (NumberFormatException e) {
                    // Skip invalid values
                }
            }
            return history;
        } catch (Exception e) {
            log.warn("Failed to get history for {}: {}", key, e.getMessage());
            return new ArrayList<>();
        }
    }

    private void storeHistory(String key, List<Double> history) {
        try {
            // Clear and repopulate
            stringRedisTemplate.delete(key);

            if (!history.isEmpty()) {
                List<String> stringValues = history.stream()
                        .map(String::valueOf)
                        .toList();
                stringRedisTemplate.opsForList().rightPushAll(key, stringValues);
                stringRedisTemplate.expire(key, TTL_HOURS, TimeUnit.HOURS);
            }
        } catch (Exception e) {
            log.error("Failed to store history for {}: {}", key, e.getMessage());
        }
    }

    private MetricRegime getStoredRegime(String key) {
        try {
            String value = stringRedisTemplate.opsForValue().get(key);
            if (value == null || value.isEmpty()) {
                return MetricRegime.NEUTRAL;
            }
            return MetricRegime.valueOf(value);
        } catch (Exception e) {
            return MetricRegime.NEUTRAL;
        }
    }

    private void storeRegime(String key, MetricRegime regime) {
        try {
            stringRedisTemplate.opsForValue().set(key, regime.name(), TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("Failed to store regime for {}: {}", key, e.getMessage());
        }
    }

    /**
     * Detect regime based on percentile and consecutive count
     *
     * Classification (from roadmap):
     * - percentile > 80 AND consecutive >= 3 -> STRONG_POSITIVE
     * - percentile > 60 OR consecutive >= 2 -> POSITIVE
     * - percentile < 20 AND consecutive >= 3 -> STRONG_NEGATIVE
     * - percentile < 40 OR consecutive >= 2 (negative) -> NEGATIVE
     * - else -> NEUTRAL
     */
    private MetricRegime detectRegime(double percentile, int consecutiveCount, boolean isPositive) {
        if (isPositive) {
            if (percentile > STRONG_PERCENTILE && consecutiveCount >= STRONG_CONSECUTIVE) {
                return MetricRegime.STRONG_POSITIVE;
            }
            if (percentile > 60.0 || consecutiveCount >= WEAK_CONSECUTIVE) {
                return MetricRegime.POSITIVE;
            }
        } else {
            if (percentile < (100 - STRONG_PERCENTILE) && consecutiveCount >= STRONG_CONSECUTIVE) {
                return MetricRegime.STRONG_NEGATIVE;
            }
            if (percentile < WEAK_PERCENTILE || consecutiveCount >= WEAK_CONSECUTIVE) {
                return MetricRegime.NEGATIVE;
            }
        }
        return MetricRegime.NEUTRAL;
    }

    /**
     * Detect regime flip/transition
     */
    private FlipType detectFlip(MetricRegime previous, MetricRegime current) {
        if (previous == null || previous == current) {
            return FlipType.NONE;
        }

        boolean wasBullish = previous == MetricRegime.STRONG_POSITIVE || previous == MetricRegime.POSITIVE;
        boolean wasBearish = previous == MetricRegime.STRONG_NEGATIVE || previous == MetricRegime.NEGATIVE;
        boolean isBullish = current == MetricRegime.STRONG_POSITIVE || current == MetricRegime.POSITIVE;
        boolean isBearish = current == MetricRegime.STRONG_NEGATIVE || current == MetricRegime.NEGATIVE;

        if (wasBearish && isBullish) {
            return FlipType.BEARISH_TO_BULLISH;
        }
        if (wasBullish && isBearish) {
            return FlipType.BULLISH_TO_BEARISH;
        }
        if (previous == MetricRegime.NEUTRAL && isBullish) {
            return FlipType.NEUTRAL_TO_BULLISH;
        }
        if (previous == MetricRegime.NEUTRAL && isBearish) {
            return FlipType.NEUTRAL_TO_BEARISH;
        }
        if (wasBullish && current == MetricRegime.NEUTRAL) {
            return FlipType.BULLISH_TO_NEUTRAL;
        }
        if (wasBearish && current == MetricRegime.NEUTRAL) {
            return FlipType.BEARISH_TO_NEUTRAL;
        }

        return FlipType.NONE;
    }
}
