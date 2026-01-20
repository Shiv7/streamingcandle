package com.kotsin.consumer.enrichment.enricher;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MTFSuperTrendAggregator - Multi-Timeframe SuperTrend Aggregation
 *
 * Solves the problem of using single-timeframe SuperTrend for swing/position trades.
 * Aggregates SuperTrend signals across timeframes with weighted consensus.
 *
 * Timeframe weights (higher = more weight):
 * - 1D:  10 (highest weight - primary trend)
 * - 4H:  8
 * - 2H:  6
 * - 1H:  5
 * - 30m: 4
 * - 15m: 3
 * - 5m:  2
 * - 1m:  1  (lowest weight - noise)
 *
 * Usage:
 * - SCALP trades: Use 1m-5m alignment (weight threshold: 3)
 * - INTRADAY trades: Use 5m-1h alignment (weight threshold: 10)
 * - SWING trades: Use 15m-4h alignment (weight threshold: 15)
 * - POSITION trades: Use 1h-1d alignment (weight threshold: 20)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MTFSuperTrendAggregator {

    private final RedisTemplate<String, String> redisTemplate;

    // Redis key prefix
    private static final String KEY_PREFIX = "smtis:mtf:supertrend";
    private static final Duration STATE_TTL = Duration.ofHours(24);

    // Timeframe weights - higher timeframes have more weight
    private static final Map<String, Integer> TIMEFRAME_WEIGHTS = Map.of(
            "1m", 1,
            "2m", 1,
            "3m", 2,
            "5m", 2,
            "15m", 3,
            "30m", 4,
            "1h", 5,
            "2h", 6,
            "4h", 8,
            "1d", 10
    );

    // Timeframe order for iteration (lowest to highest)
    private static final List<String> TIMEFRAMES_ORDERED = List.of(
            "1m", "2m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "1d"
    );

    // In-memory cache for quick access
    private final Map<String, MTFState> stateCache = new ConcurrentHashMap<>();

    // ======================== PUBLIC API ========================

    /**
     * Update SuperTrend state for a specific timeframe
     * Called by TechnicalIndicatorEnricher after calculating SuperTrend
     */
    public void updateState(String familyId, String timeframe, boolean bullish,
                           double superTrendValue, boolean flipped) {
        String key = getKey(familyId);

        // Get or create state
        MTFState state = stateCache.computeIfAbsent(familyId, k -> loadState(familyId));

        // Update timeframe state
        TimeframeState tfState = TimeframeState.builder()
                .timeframe(timeframe)
                .bullish(bullish)
                .superTrendValue(superTrendValue)
                .flipped(flipped)
                .updatedAt(Instant.now())
                .build();

        state.getTimeframeStates().put(timeframe, tfState);
        state.setLastUpdated(Instant.now());

        // Recalculate aggregation
        recalculateAggregation(state);

        // Persist to Redis
        persistState(familyId, state);

        log.trace("[MTF-ST] Updated {} [{}] bullish={} value={} flipped={} | aggregate={}",
                familyId, timeframe, bullish, superTrendValue, flipped,
                state.getAggregatedDirection());
    }

    /**
     * Get aggregated SuperTrend direction for a family
     *
     * @param familyId Family ID
     * @return Aggregated direction (BULLISH, BEARISH, NEUTRAL, CONFLICTING)
     */
    public AggregatedDirection getAggregatedDirection(String familyId) {
        MTFState state = stateCache.computeIfAbsent(familyId, k -> loadState(familyId));
        return state.getAggregatedDirection();
    }

    /**
     * Get detailed MTF analysis for a family
     */
    public MTFAnalysis getAnalysis(String familyId) {
        MTFState state = stateCache.computeIfAbsent(familyId, k -> loadState(familyId));
        return buildAnalysis(state);
    }

    /**
     * Check if higher timeframes are aligned bullish
     * Used for SWING_LONG validation
     *
     * @param familyId Family ID
     * @param minTimeframe Minimum timeframe to consider (e.g., "15m" for swing)
     * @param minAlignment Minimum bullish percentage (0.0-1.0)
     */
    public boolean isHTFBullish(String familyId, String minTimeframe, double minAlignment) {
        MTFState state = stateCache.computeIfAbsent(familyId, k -> loadState(familyId));

        int minTfIndex = TIMEFRAMES_ORDERED.indexOf(minTimeframe);
        if (minTfIndex < 0) minTfIndex = 0;

        int bullishWeight = 0;
        int totalWeight = 0;

        for (int i = minTfIndex; i < TIMEFRAMES_ORDERED.size(); i++) {
            String tf = TIMEFRAMES_ORDERED.get(i);
            TimeframeState tfState = state.getTimeframeStates().get(tf);
            if (tfState != null && isRecent(tfState)) {
                int weight = TIMEFRAME_WEIGHTS.getOrDefault(tf, 1);
                totalWeight += weight;
                if (tfState.isBullish()) {
                    bullishWeight += weight;
                }
            }
        }

        if (totalWeight == 0) return false;
        double alignment = (double) bullishWeight / totalWeight;
        return alignment >= minAlignment;
    }

    /**
     * Check if higher timeframes are aligned bearish
     * Used for SWING_SHORT validation
     */
    public boolean isHTFBearish(String familyId, String minTimeframe, double minAlignment) {
        MTFState state = stateCache.computeIfAbsent(familyId, k -> loadState(familyId));

        int minTfIndex = TIMEFRAMES_ORDERED.indexOf(minTimeframe);
        if (minTfIndex < 0) minTfIndex = 0;

        int bearishWeight = 0;
        int totalWeight = 0;

        for (int i = minTfIndex; i < TIMEFRAMES_ORDERED.size(); i++) {
            String tf = TIMEFRAMES_ORDERED.get(i);
            TimeframeState tfState = state.getTimeframeStates().get(tf);
            if (tfState != null && isRecent(tfState)) {
                int weight = TIMEFRAME_WEIGHTS.getOrDefault(tf, 1);
                totalWeight += weight;
                if (!tfState.isBullish()) {
                    bearishWeight += weight;
                }
            }
        }

        if (totalWeight == 0) return false;
        double alignment = (double) bearishWeight / totalWeight;
        return alignment >= minAlignment;
    }

    /**
     * Check MTF alignment for a specific trading horizon
     *
     * @param familyId Family ID
     * @param horizon Trading horizon (SCALP, INTRADAY, SWING, POSITION)
     * @param direction Expected direction (true=bullish, false=bearish)
     * @return true if aligned, false otherwise
     */
    public boolean isAlignedForHorizon(String familyId, TradingHorizon horizon, boolean direction) {
        return switch (horizon) {
            case SCALP -> direction
                    ? isHTFBullish(familyId, "1m", 0.5)   // 50% of 1m-5m bullish
                    : isHTFBearish(familyId, "1m", 0.5);
            case INTRADAY -> direction
                    ? isHTFBullish(familyId, "5m", 0.6)   // 60% of 5m-1h bullish
                    : isHTFBearish(familyId, "5m", 0.6);
            case SWING -> direction
                    ? isHTFBullish(familyId, "15m", 0.7)  // 70% of 15m-4h bullish
                    : isHTFBearish(familyId, "15m", 0.7);
            case POSITION -> direction
                    ? isHTFBullish(familyId, "1h", 0.8)   // 80% of 1h-1d bullish
                    : isHTFBearish(familyId, "1h", 0.8);
        };
    }

    /**
     * Get the dominant direction from higher timeframes
     * Returns the direction that has majority weight from 15m+ timeframes
     */
    public boolean getDominantHTFDirection(String familyId) {
        MTFState state = stateCache.computeIfAbsent(familyId, k -> loadState(familyId));
        return state.getWeightedBullishScore() > state.getWeightedBearishScore();
    }

    /**
     * Check if there's a timeframe divergence (lower TFs vs higher TFs)
     * Useful for detecting potential reversals
     */
    public boolean hasTimeframeDivergence(String familyId) {
        MTFState state = stateCache.computeIfAbsent(familyId, k -> loadState(familyId));
        return state.getAggregatedDirection() == AggregatedDirection.CONFLICTING;
    }

    // ======================== INTERNAL METHODS ========================

    private void recalculateAggregation(MTFState state) {
        int bullishWeight = 0;
        int bearishWeight = 0;
        int totalWeight = 0;
        int bullishCount = 0;
        int bearishCount = 0;
        int htfBullishWeight = 0;  // 15m+ only
        int htfBearishWeight = 0;
        int htfTotalWeight = 0;

        for (String tf : TIMEFRAMES_ORDERED) {
            TimeframeState tfState = state.getTimeframeStates().get(tf);
            if (tfState != null && isRecent(tfState)) {
                int weight = TIMEFRAME_WEIGHTS.getOrDefault(tf, 1);
                totalWeight += weight;

                if (tfState.isBullish()) {
                    bullishWeight += weight;
                    bullishCount++;
                } else {
                    bearishWeight += weight;
                    bearishCount++;
                }

                // Track HTF separately (15m+)
                int tfIndex = TIMEFRAMES_ORDERED.indexOf(tf);
                if (tfIndex >= TIMEFRAMES_ORDERED.indexOf("15m")) {
                    htfTotalWeight += weight;
                    if (tfState.isBullish()) {
                        htfBullishWeight += weight;
                    } else {
                        htfBearishWeight += weight;
                    }
                }
            }
        }

        state.setWeightedBullishScore(bullishWeight);
        state.setWeightedBearishScore(bearishWeight);
        state.setTotalWeight(totalWeight);
        state.setBullishCount(bullishCount);
        state.setBearishCount(bearishCount);
        state.setHtfBullishWeight(htfBullishWeight);
        state.setHtfBearishWeight(htfBearishWeight);

        // Determine aggregated direction
        if (totalWeight == 0) {
            state.setAggregatedDirection(AggregatedDirection.NEUTRAL);
        } else {
            double bullishPct = (double) bullishWeight / totalWeight;
            double bearishPct = (double) bearishWeight / totalWeight;

            // Check for HTF dominance (15m+ timeframes)
            boolean htfBullish = htfTotalWeight > 0 && htfBullishWeight > htfBearishWeight;
            boolean htfBearish = htfTotalWeight > 0 && htfBearishWeight > htfBullishWeight;

            if (bullishPct >= 0.7) {
                state.setAggregatedDirection(AggregatedDirection.BULLISH);
            } else if (bearishPct >= 0.7) {
                state.setAggregatedDirection(AggregatedDirection.BEARISH);
            } else if (htfBullish && bullishPct >= 0.5) {
                // HTF bullish and overall slightly bullish -> BULLISH
                state.setAggregatedDirection(AggregatedDirection.BULLISH);
            } else if (htfBearish && bearishPct >= 0.5) {
                // HTF bearish and overall slightly bearish -> BEARISH
                state.setAggregatedDirection(AggregatedDirection.BEARISH);
            } else if (Math.abs(bullishPct - bearishPct) < 0.2) {
                state.setAggregatedDirection(AggregatedDirection.CONFLICTING);
            } else {
                state.setAggregatedDirection(bullishPct > bearishPct
                        ? AggregatedDirection.BULLISH
                        : AggregatedDirection.BEARISH);
            }
        }
    }

    private MTFAnalysis buildAnalysis(MTFState state) {
        Map<String, Boolean> timeframeDirections = new LinkedHashMap<>();
        for (String tf : TIMEFRAMES_ORDERED) {
            TimeframeState tfState = state.getTimeframeStates().get(tf);
            if (tfState != null && isRecent(tfState)) {
                timeframeDirections.put(tf, tfState.isBullish());
            }
        }

        double bullishPct = state.getTotalWeight() > 0
                ? (double) state.getWeightedBullishScore() / state.getTotalWeight()
                : 0.5;

        return MTFAnalysis.builder()
                .familyId(state.getFamilyId())
                .aggregatedDirection(state.getAggregatedDirection())
                .timeframeDirections(timeframeDirections)
                .bullishPercentage(bullishPct)
                .bearishPercentage(1.0 - bullishPct)
                .bullishCount(state.getBullishCount())
                .bearishCount(state.getBearishCount())
                .totalTimeframes(state.getBullishCount() + state.getBearishCount())
                .htfBullish(state.getHtfBullishWeight() > state.getHtfBearishWeight())
                .hasConflict(state.getAggregatedDirection() == AggregatedDirection.CONFLICTING)
                .lastUpdated(state.getLastUpdated())
                .build();
    }

    private boolean isRecent(TimeframeState tfState) {
        // Consider state recent if updated within last 2 hours
        return tfState.getUpdatedAt() != null &&
               tfState.getUpdatedAt().isAfter(Instant.now().minus(Duration.ofHours(2)));
    }

    private String getKey(String familyId) {
        return KEY_PREFIX + ":" + familyId;
    }

    private MTFState loadState(String familyId) {
        String key = getKey(familyId);
        try {
            Map<Object, Object> data = redisTemplate.opsForHash().entries(key);
            if (data != null && !data.isEmpty()) {
                MTFState state = MTFState.builder()
                        .familyId(familyId)
                        .timeframeStates(new ConcurrentHashMap<>())
                        .build();

                for (Map.Entry<Object, Object> entry : data.entrySet()) {
                    String tf = entry.getKey().toString();
                    String[] parts = entry.getValue().toString().split(",");
                    if (parts.length >= 3) {
                        TimeframeState tfState = TimeframeState.builder()
                                .timeframe(tf)
                                .bullish(Boolean.parseBoolean(parts[0]))
                                .superTrendValue(Double.parseDouble(parts[1]))
                                .flipped(Boolean.parseBoolean(parts[2]))
                                .updatedAt(parts.length > 3 ? Instant.parse(parts[3]) : Instant.now())
                                .build();
                        state.getTimeframeStates().put(tf, tfState);
                    }
                }

                recalculateAggregation(state);
                return state;
            }
        } catch (Exception e) {
            log.warn("[MTF-ST] Failed to load state for {}: {}", familyId, e.getMessage());
        }

        return MTFState.builder()
                .familyId(familyId)
                .timeframeStates(new ConcurrentHashMap<>())
                .aggregatedDirection(AggregatedDirection.NEUTRAL)
                .build();
    }

    private void persistState(String familyId, MTFState state) {
        String key = getKey(familyId);
        try {
            Map<String, String> data = new HashMap<>();
            for (Map.Entry<String, TimeframeState> entry : state.getTimeframeStates().entrySet()) {
                TimeframeState tf = entry.getValue();
                data.put(entry.getKey(), String.format("%b,%f,%b,%s",
                        tf.isBullish(), tf.getSuperTrendValue(), tf.isFlipped(),
                        tf.getUpdatedAt() != null ? tf.getUpdatedAt().toString() : Instant.now().toString()));
            }
            redisTemplate.opsForHash().putAll(key, data);
            redisTemplate.expire(key, STATE_TTL);
        } catch (Exception e) {
            log.warn("[MTF-ST] Failed to persist state for {}: {}", familyId, e.getMessage());
        }
    }

    /**
     * Clear state for a family (used for testing/reset)
     */
    public void clearState(String familyId) {
        stateCache.remove(familyId);
        redisTemplate.delete(getKey(familyId));
    }

    // ======================== MODELS ========================

    public enum AggregatedDirection {
        BULLISH,      // Strong bullish consensus (70%+ weight)
        BEARISH,      // Strong bearish consensus (70%+ weight)
        NEUTRAL,      // No data or insufficient data
        CONFLICTING   // Mixed signals - HTF vs LTF divergence
    }

    public enum TradingHorizon {
        SCALP,        // 1m-5m trades
        INTRADAY,     // 5m-1h trades
        SWING,        // 15m-4h trades
        POSITION      // 1h-1d trades
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class MTFState {
        private String familyId;
        private Map<String, TimeframeState> timeframeStates;
        private AggregatedDirection aggregatedDirection;
        private int weightedBullishScore;
        private int weightedBearishScore;
        private int totalWeight;
        private int bullishCount;
        private int bearishCount;
        private int htfBullishWeight;
        private int htfBearishWeight;
        private Instant lastUpdated;
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class TimeframeState {
        private String timeframe;
        private boolean bullish;
        private double superTrendValue;
        private boolean flipped;
        private Instant updatedAt;
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class MTFAnalysis {
        private String familyId;
        private AggregatedDirection aggregatedDirection;
        private Map<String, Boolean> timeframeDirections;  // timeframe -> bullish
        private double bullishPercentage;
        private double bearishPercentage;
        private int bullishCount;
        private int bearishCount;
        private int totalTimeframes;
        private boolean htfBullish;  // Higher timeframes (15m+) consensus
        private boolean hasConflict;
        private Instant lastUpdated;

        public String getSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append(aggregatedDirection).append(" (");
            sb.append(String.format("%.0f%% bull", bullishPercentage * 100));
            sb.append(", HTF:").append(htfBullish ? "BULL" : "BEAR");
            if (hasConflict) sb.append(", CONFLICT");
            sb.append(") | ");

            for (Map.Entry<String, Boolean> e : timeframeDirections.entrySet()) {
                sb.append(e.getKey()).append(":").append(e.getValue() ? "▲" : "▼").append(" ");
            }
            return sb.toString().trim();
        }
    }
}
