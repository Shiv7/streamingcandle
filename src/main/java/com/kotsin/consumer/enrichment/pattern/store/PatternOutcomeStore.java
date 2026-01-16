package com.kotsin.consumer.enrichment.pattern.store;

import com.kotsin.consumer.enrichment.pattern.model.HistoricalStats;
import com.kotsin.consumer.enrichment.pattern.model.PatternSignal;
import com.kotsin.consumer.enrichment.pattern.registry.SequenceTemplateRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PatternOutcomeStore - Stores and manages pattern outcome statistics
 *
 * Uses MongoDB for persistence:
 * - 'pattern_stats' collection: Global pattern statistics
 * - 'pattern_stats_by_family' collection: Per-family statistics
 *
 * Receives trade outcomes from tradeExecutionModule via Kafka
 * and updates historical statistics for pattern learning.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PatternOutcomeStore {

    private final MongoTemplate mongoTemplate;
    private final SequenceTemplateRegistry templateRegistry;

    private static final String STATS_COLLECTION = "pattern_stats";
    private static final String FAMILY_STATS_COLLECTION = "pattern_stats_by_family";

    /**
     * In-memory cache of pattern statistics (updated periodically from MongoDB)
     */
    private final Map<String, HistoricalStats> statsCache = new ConcurrentHashMap<>();

    /**
     * Per-family stats cache
     */
    private final Map<String, Map<String, HistoricalStats>> familyStatsCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void initialize() {
        log.info("[PATTERN_STORE] Initializing pattern outcome store...");
        loadAllStats();
        log.info("[PATTERN_STORE] Loaded {} global pattern stats", statsCache.size());
    }

    // ======================== STATS RETRIEVAL ========================

    /**
     * Get global stats for a pattern template
     */
    public HistoricalStats getStats(String templateId) {
        return statsCache.computeIfAbsent(templateId, this::loadStats);
    }

    /**
     * Get stats for a pattern in a specific family
     */
    public HistoricalStats getFamilyStats(String templateId, String familyId) {
        Map<String, HistoricalStats> familyStats = familyStatsCache
                .computeIfAbsent(familyId, k -> new ConcurrentHashMap<>());

        return familyStats.computeIfAbsent(templateId, k -> loadFamilyStats(templateId, familyId));
    }

    /**
     * Get combined stats (weighted blend of global and family-specific)
     */
    public HistoricalStats getCombinedStats(String templateId, String familyId) {
        HistoricalStats globalStats = getStats(templateId);
        HistoricalStats familyStats = getFamilyStats(templateId, familyId);

        // If no family stats, return global
        if (familyStats == null || familyStats.getOccurrences() < 10) {
            return globalStats;
        }

        // If no global stats, return family
        if (globalStats == null || globalStats.getOccurrences() == 0) {
            return familyStats;
        }

        // Blend based on sample sizes
        return blendStats(globalStats, familyStats);
    }

    /**
     * Get all stats for a family
     */
    public List<HistoricalStats> getAllFamilyStats(String familyId) {
        return new ArrayList<>(familyStatsCache.getOrDefault(familyId, Collections.emptyMap()).values());
    }

    // ======================== OUTCOME RECORDING ========================

    /**
     * Record a trade outcome from tradeExecutionModule
     * Called when PaperTradeOutcome is received via Kafka
     */
    public void recordOutcome(PatternOutcome outcome) {
        log.debug("[PATTERN_STORE] Recording outcome for pattern {}: success={}, pnl={}%",
                outcome.patternId, outcome.success, String.format("%.2f", outcome.pnlPct));

        // Update global stats
        updateGlobalStats(outcome);

        // Update family-specific stats
        if (outcome.familyId != null) {
            updateFamilyStats(outcome);
        }

        // Update template registry with new stats
        HistoricalStats updatedStats = getStats(outcome.patternId);
        if (updatedStats != null) {
            templateRegistry.updateTemplateStats(outcome.patternId, updatedStats);
        }
    }

    /**
     * Record that a pattern expired without a trade result
     */
    public void recordExpired(String patternId, String familyId) {
        // Update global stats
        updateExpiredGlobal(patternId);

        // Update family stats
        if (familyId != null) {
            updateExpiredFamily(patternId, familyId);
        }
    }

    // ======================== MONGODB OPERATIONS ========================

    /**
     * Load stats from MongoDB
     */
    private HistoricalStats loadStats(String templateId) {
        try {
            Query query = Query.query(Criteria.where("templateId").is(templateId));
            HistoricalStats stats = mongoTemplate.findOne(query, HistoricalStats.class, STATS_COLLECTION);

            if (stats == null) {
                // Create new stats entry
                stats = HistoricalStats.empty(templateId);
                mongoTemplate.save(stats, STATS_COLLECTION);
            }

            return stats;
        } catch (Exception e) {
            log.warn("[PATTERN_STORE] Failed to load stats for {}: {}", templateId, e.getMessage());
            return HistoricalStats.empty(templateId);
        }
    }

    /**
     * Load family-specific stats
     */
    private HistoricalStats loadFamilyStats(String templateId, String familyId) {
        try {
            Query query = Query.query(
                    Criteria.where("templateId").is(templateId)
                            .and("familyId").is(familyId));
            HistoricalStats stats = mongoTemplate.findOne(query, HistoricalStats.class, FAMILY_STATS_COLLECTION);

            if (stats == null) {
                stats = HistoricalStats.builder()
                        .templateId(templateId)
                        .familyId(familyId)
                        .lastUpdated(Instant.now())
                        .build();
                mongoTemplate.save(stats, FAMILY_STATS_COLLECTION);
            }

            return stats;
        } catch (Exception e) {
            log.warn("[PATTERN_STORE] Failed to load family stats for {}/{}: {}",
                    templateId, familyId, e.getMessage());
            return null;
        }
    }

    /**
     * Load all stats into cache
     */
    private void loadAllStats() {
        try {
            List<HistoricalStats> allStats = mongoTemplate.findAll(HistoricalStats.class, STATS_COLLECTION);
            for (HistoricalStats stats : allStats) {
                statsCache.put(stats.getTemplateId(), stats);
            }
        } catch (Exception e) {
            log.warn("[PATTERN_STORE] Failed to load all stats: {}", e.getMessage());
        }
    }

    /**
     * Update global stats in MongoDB
     */
    private void updateGlobalStats(PatternOutcome outcome) {
        try {
            HistoricalStats stats = getStats(outcome.patternId);

            // Update stats
            stats.recordOutcome(outcome.success, outcome.pnlPct, outcome.timeToOutcomeMs);

            // Save to MongoDB
            Query query = Query.query(Criteria.where("templateId").is(outcome.patternId));
            Update update = new Update()
                    .set("occurrences", stats.getOccurrences())
                    .set("successes", stats.getSuccesses())
                    .set("failures", stats.getFailures())
                    .set("avgGainOnSuccess", stats.getAvgGainOnSuccess())
                    .set("avgLossOnFailure", stats.getAvgLossOnFailure())
                    .set("maxGain", stats.getMaxGain())
                    .set("maxLoss", stats.getMaxLoss())
                    .set("totalPnlPct", stats.getTotalPnlPct())
                    .set("avgTimeToOutcomeMs", stats.getAvgTimeToOutcomeMs())
                    .set("lastOccurrence", stats.getLastOccurrence())
                    .set("lastUpdated", Instant.now());

            mongoTemplate.upsert(query, update, STATS_COLLECTION);

            // Update cache
            statsCache.put(outcome.patternId, stats);

            log.debug("[PATTERN_STORE] Updated global stats for {}: {} trades, {}% win rate",
                    outcome.patternId, stats.getOccurrences(),
                    String.format("%.0f", stats.getSuccessRate() * 100));

        } catch (Exception e) {
            log.error("[PATTERN_STORE] Failed to update global stats: {}", e.getMessage());
        }
    }

    /**
     * Update family-specific stats
     */
    private void updateFamilyStats(PatternOutcome outcome) {
        try {
            HistoricalStats stats = getFamilyStats(outcome.patternId, outcome.familyId);
            if (stats == null) {
                stats = HistoricalStats.builder()
                        .templateId(outcome.patternId)
                        .familyId(outcome.familyId)
                        .lastUpdated(Instant.now())
                        .build();
            }

            stats.recordOutcome(outcome.success, outcome.pnlPct, outcome.timeToOutcomeMs);

            Query query = Query.query(
                    Criteria.where("templateId").is(outcome.patternId)
                            .and("familyId").is(outcome.familyId));

            Update update = new Update()
                    .set("occurrences", stats.getOccurrences())
                    .set("successes", stats.getSuccesses())
                    .set("failures", stats.getFailures())
                    .set("avgGainOnSuccess", stats.getAvgGainOnSuccess())
                    .set("avgLossOnFailure", stats.getAvgLossOnFailure())
                    .set("totalPnlPct", stats.getTotalPnlPct())
                    .set("lastOccurrence", stats.getLastOccurrence())
                    .set("lastUpdated", Instant.now());

            mongoTemplate.upsert(query, update, FAMILY_STATS_COLLECTION);

            // Update cache
            familyStatsCache.computeIfAbsent(outcome.familyId, k -> new ConcurrentHashMap<>())
                    .put(outcome.patternId, stats);

        } catch (Exception e) {
            log.error("[PATTERN_STORE] Failed to update family stats: {}", e.getMessage());
        }
    }

    /**
     * Update expired count in global stats
     */
    private void updateExpiredGlobal(String patternId) {
        try {
            HistoricalStats stats = getStats(patternId);
            stats.recordExpired();

            Query query = Query.query(Criteria.where("templateId").is(patternId));
            Update update = new Update()
                    .set("occurrences", stats.getOccurrences())
                    .set("expired", stats.getExpired())
                    .set("lastUpdated", Instant.now());

            mongoTemplate.upsert(query, update, STATS_COLLECTION);
            statsCache.put(patternId, stats);

        } catch (Exception e) {
            log.error("[PATTERN_STORE] Failed to update expired count: {}", e.getMessage());
        }
    }

    /**
     * Update expired count in family stats
     */
    private void updateExpiredFamily(String patternId, String familyId) {
        try {
            HistoricalStats stats = getFamilyStats(patternId, familyId);
            if (stats != null) {
                stats.recordExpired();

                Query query = Query.query(
                        Criteria.where("templateId").is(patternId)
                                .and("familyId").is(familyId));
                Update update = new Update()
                        .set("occurrences", stats.getOccurrences())
                        .set("expired", stats.getExpired())
                        .set("lastUpdated", Instant.now());

                mongoTemplate.upsert(query, update, FAMILY_STATS_COLLECTION);
            }
        } catch (Exception e) {
            log.error("[PATTERN_STORE] Failed to update family expired count: {}", e.getMessage());
        }
    }

    // ======================== STATS BLENDING ========================

    /**
     * Blend global and family stats based on sample sizes
     */
    private HistoricalStats blendStats(HistoricalStats global, HistoricalStats family) {
        // Calculate weights based on sample sizes
        int totalSamples = global.getOccurrences() + family.getOccurrences();
        double globalWeight = 0.4; // Base weight for global
        double familyWeight = 0.6; // Base weight for family

        // Adjust weights based on sample sizes
        if (family.getOccurrences() < 30) {
            familyWeight = 0.3;
            globalWeight = 0.7;
        } else if (family.getOccurrences() > 100) {
            familyWeight = 0.8;
            globalWeight = 0.2;
        }

        // Create blended stats
        return HistoricalStats.builder()
                .templateId(global.getTemplateId())
                .familyId(family.getFamilyId())
                .occurrences(family.getOccurrences()) // Use family occurrence count
                .successes(family.getSuccesses())
                .failures(family.getFailures())
                .avgGainOnSuccess(global.getAvgGainOnSuccess() * globalWeight +
                        family.getAvgGainOnSuccess() * familyWeight)
                .avgLossOnFailure(global.getAvgLossOnFailure() * globalWeight +
                        family.getAvgLossOnFailure() * familyWeight)
                .recentSuccessRate(family.getRecentSuccessRate())
                .recentExpectedValue(family.getRecentExpectedValue())
                .build();
    }

    // ======================== SCHEDULED TASKS ========================

    /**
     * Periodically refresh stats from MongoDB
     */
    @Scheduled(fixedRate = 300_000) // Every 5 minutes
    public void refreshStats() {
        log.debug("[PATTERN_STORE] Refreshing pattern stats from MongoDB...");
        loadAllStats();
    }

    /**
     * Sync template registry with loaded stats
     */
    @Scheduled(fixedRate = 60_000) // Every minute
    public void syncTemplateStats() {
        for (Map.Entry<String, HistoricalStats> entry : statsCache.entrySet()) {
            templateRegistry.updateTemplateStats(entry.getKey(), entry.getValue());
        }
    }

    // ======================== SUMMARY ========================

    /**
     * Get performance summary across all patterns
     */
    public PerformanceSummary getPerformanceSummary() {
        int totalTrades = 0;
        int totalWins = 0;
        double totalPnl = 0;
        String bestPattern = null;
        double bestEV = Double.NEGATIVE_INFINITY;
        String worstPattern = null;
        double worstEV = Double.POSITIVE_INFINITY;

        for (HistoricalStats stats : statsCache.values()) {
            totalTrades += stats.getOccurrences();
            totalWins += stats.getSuccesses();
            totalPnl += stats.getTotalPnlPct();

            double ev = stats.getExpectedValue();
            if (ev > bestEV) {
                bestEV = ev;
                bestPattern = stats.getTemplateId();
            }
            if (ev < worstEV && stats.getOccurrences() > 10) {
                worstEV = ev;
                worstPattern = stats.getTemplateId();
            }
        }

        double overallWinRate = totalTrades > 0 ? (double) totalWins / totalTrades : 0;

        return new PerformanceSummary(
                statsCache.size(),
                totalTrades,
                overallWinRate,
                totalPnl,
                bestPattern,
                bestEV,
                worstPattern,
                worstEV
        );
    }

    public record PerformanceSummary(
            int patternsTracked,
            int totalTrades,
            double overallWinRate,
            double totalPnlPct,
            String bestPattern,
            double bestEV,
            String worstPattern,
            double worstEV
    ) {
        @Override
        public String toString() {
            return String.format("Performance: %d patterns, %d trades, %.1f%% win rate, %.2f%% total P&L",
                    patternsTracked, totalTrades, overallWinRate * 100, totalPnlPct);
        }
    }

    // ======================== OUTCOME DTO ========================

    /**
     * Pattern outcome received from tradeExecutionModule
     */
    public record PatternOutcome(
            String patternId,
            String sequenceId,
            String signalId,
            String familyId,
            boolean success,
            double pnlPct,
            long timeToOutcomeMs,
            boolean target1Hit,
            boolean target2Hit,
            boolean stopHit
    ) {}
}
