package com.kotsin.consumer.enrichment.signal.outcome;

import com.kotsin.consumer.enrichment.signal.model.TradingSignal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SignalOutcomeStore - Stores and manages trading signal outcomes
 *
 * Provides:
 * - Outcome persistence to MongoDB
 * - Performance statistics calculation
 * - Cached aggregations for fast access
 * - Signal-to-pattern attribution
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignalOutcomeStore {

    private final MongoTemplate mongoTemplate;

    private static final String COLLECTION = "signal_outcomes";

    /**
     * Cache of performance stats by source type
     */
    private final Map<String, SourcePerformance> sourcePerformanceCache = new ConcurrentHashMap<>();

    /**
     * Cache of performance stats by family
     */
    private final Map<String, FamilyPerformance> familyPerformanceCache = new ConcurrentHashMap<>();

    /**
     * Statistics
     */
    private final AtomicLong totalOutcomesRecorded = new AtomicLong(0);
    private final AtomicLong totalWins = new AtomicLong(0);
    private final AtomicLong totalLosses = new AtomicLong(0);

    // ======================== OUTCOME RECORDING ========================

    /**
     * Record a signal outcome
     */
    public void recordOutcome(SignalOutcome outcome) {
        if (outcome == null || outcome.getSignalId() == null) {
            return;
        }

        try {
            // Calculate derived fields
            calculateDerivedFields(outcome);

            // Save to MongoDB
            mongoTemplate.save(outcome, COLLECTION);

            // Update statistics
            totalOutcomesRecorded.incrementAndGet();
            if (outcome.isProfitable()) {
                totalWins.incrementAndGet();
            } else if (outcome.getOutcome() == SignalOutcome.Outcome.LOSS) {
                totalLosses.incrementAndGet();
            }

            // Invalidate relevant caches
            if (outcome.getSource() != null) {
                sourcePerformanceCache.remove(outcome.getSource());
            }
            if (outcome.getFamilyId() != null) {
                familyPerformanceCache.remove(outcome.getFamilyId());
            }

            log.info("[OUTCOME_STORE] Recorded outcome for signal {}: {} {:.2f}%",
                    outcome.getSignalId(), outcome.getOutcome(), outcome.getPnlPct());

        } catch (Exception e) {
            log.error("[OUTCOME_STORE] Failed to record outcome for signal {}: {}",
                    outcome.getSignalId(), e.getMessage());
        }
    }

    /**
     * Record outcome from trade execution
     */
    public void recordFromExecution(TradingSignal signal, SignalOutcome.TradeExecution execution) {
        SignalOutcome outcome = SignalOutcome.fromSignal(signal, execution);

        // Fill in execution details
        outcome.setEnteredAt(execution.getEnteredAt());
        outcome.setClosedAt(execution.getClosedAt());
        outcome.setActualEntryPrice(execution.getEntryPrice());
        outcome.setExitPrice(execution.getExitPrice());
        outcome.setPositionSize(execution.getPositionSize());
        outcome.setTarget1Hit(execution.isTarget1Hit());
        outcome.setTarget2Hit(execution.isTarget2Hit());
        outcome.setTarget3Hit(execution.isTarget3Hit());
        outcome.setStopLossHit(execution.isStopLossHit());

        // Determine outcome
        if (execution.isStopLossHit()) {
            outcome.setOutcome(SignalOutcome.Outcome.LOSS);
            outcome.setExitReason(SignalOutcome.ExitReason.STOP_LOSS_HIT);
        } else if (execution.isTarget2Hit()) {
            outcome.setOutcome(SignalOutcome.Outcome.WIN);
            outcome.setExitReason(SignalOutcome.ExitReason.TARGET_2_HIT);
        } else if (execution.isTarget1Hit()) {
            outcome.setOutcome(SignalOutcome.Outcome.WIN);
            outcome.setExitReason(SignalOutcome.ExitReason.TARGET_1_HIT);
        } else {
            // Determine by P&L
            double pnl = calculatePnl(signal, execution);
            if (pnl > 0) {
                outcome.setOutcome(SignalOutcome.Outcome.WIN);
            } else if (pnl < -0.05) { // 0.05% threshold for breakeven
                outcome.setOutcome(SignalOutcome.Outcome.LOSS);
            } else {
                outcome.setOutcome(SignalOutcome.Outcome.BREAKEVEN);
            }
        }

        // Calculate MFE/MAE
        calculateExcursions(outcome, signal, execution);

        recordOutcome(outcome);
    }

    /**
     * Record expired signal
     */
    public void recordExpired(TradingSignal signal) {
        SignalOutcome outcome = SignalOutcome.expired(signal);
        recordOutcome(outcome);
    }

    /**
     * Record cancelled signal
     */
    public void recordCancelled(TradingSignal signal, String reason) {
        SignalOutcome outcome = SignalOutcome.cancelled(signal, reason);
        recordOutcome(outcome);
    }

    // ======================== QUERIES ========================

    /**
     * Get outcome by signal ID
     */
    public SignalOutcome getOutcome(String signalId) {
        Query query = Query.query(Criteria.where("signalId").is(signalId));
        return mongoTemplate.findOne(query, SignalOutcome.class, COLLECTION);
    }

    /**
     * Get recent outcomes for a family
     */
    public List<SignalOutcome> getRecentOutcomes(String familyId, int limit) {
        Query query = Query.query(Criteria.where("familyId").is(familyId))
                .with(Sort.by(Sort.Direction.DESC, "closedAt"))
                .limit(limit);
        return mongoTemplate.find(query, SignalOutcome.class, COLLECTION);
    }

    /**
     * Get outcomes by pattern ID
     */
    public List<SignalOutcome> getOutcomesByPattern(String patternId, int limit) {
        Query query = Query.query(Criteria.where("patternId").is(patternId))
                .with(Sort.by(Sort.Direction.DESC, "closedAt"))
                .limit(limit);
        return mongoTemplate.find(query, SignalOutcome.class, COLLECTION);
    }

    /**
     * Get outcomes by source
     */
    public List<SignalOutcome> getOutcomesBySource(String source, int limit) {
        Query query = Query.query(Criteria.where("source").is(source))
                .with(Sort.by(Sort.Direction.DESC, "closedAt"))
                .limit(limit);
        return mongoTemplate.find(query, SignalOutcome.class, COLLECTION);
    }

    /**
     * Get outcomes in date range
     */
    public List<SignalOutcome> getOutcomesInRange(Instant start, Instant end) {
        Query query = Query.query(Criteria.where("closedAt").gte(start).lte(end))
                .with(Sort.by(Sort.Direction.DESC, "closedAt"));
        return mongoTemplate.find(query, SignalOutcome.class, COLLECTION);
    }

    // ======================== PERFORMANCE STATS ========================

    /**
     * Get performance stats by source
     */
    public SourcePerformance getSourcePerformance(String source) {
        return sourcePerformanceCache.computeIfAbsent(source, this::calculateSourcePerformance);
    }

    /**
     * Get performance stats by family
     */
    public FamilyPerformance getFamilyPerformance(String familyId) {
        return familyPerformanceCache.computeIfAbsent(familyId, this::calculateFamilyPerformance);
    }

    /**
     * Get overall performance summary
     */
    public OverallPerformance getOverallPerformance() {
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);

        Query query = Query.query(Criteria.where("closedAt").gte(cutoff)
                .and("outcome").in(SignalOutcome.Outcome.WIN, SignalOutcome.Outcome.LOSS));

        List<SignalOutcome> outcomes = mongoTemplate.find(query, SignalOutcome.class, COLLECTION);

        if (outcomes.isEmpty()) {
            return OverallPerformance.empty();
        }

        long wins = outcomes.stream().filter(SignalOutcome::isProfitable).count();
        long losses = outcomes.size() - wins;
        double totalPnl = outcomes.stream().mapToDouble(SignalOutcome::getPnlPct).sum();
        double avgPnl = totalPnl / outcomes.size();
        double avgWin = outcomes.stream().filter(o -> o.getPnlPct() > 0).mapToDouble(SignalOutcome::getPnlPct).average().orElse(0);
        double avgLoss = outcomes.stream().filter(o -> o.getPnlPct() < 0).mapToDouble(SignalOutcome::getPnlPct).average().orElse(0);
        double avgRMultiple = outcomes.stream().mapToDouble(SignalOutcome::getRMultiple).average().orElse(0);
        double target1HitRate = (double) outcomes.stream().filter(SignalOutcome::isTarget1Hit).count() / outcomes.size();
        double target2HitRate = (double) outcomes.stream().filter(SignalOutcome::isTarget2Hit).count() / outcomes.size();

        return OverallPerformance.builder()
                .totalTrades(outcomes.size())
                .wins((int) wins)
                .losses((int) losses)
                .winRate((double) wins / outcomes.size())
                .totalPnlPct(totalPnl)
                .avgPnlPct(avgPnl)
                .avgWinPct(avgWin)
                .avgLossPct(avgLoss)
                .avgRMultiple(avgRMultiple)
                .profitFactor(avgLoss != 0 ? Math.abs(avgWin * wins / (avgLoss * losses)) : 0)
                .target1HitRate(target1HitRate)
                .target2HitRate(target2HitRate)
                .periodDays(30)
                .calculatedAt(Instant.now())
                .build();
    }

    /**
     * Get performance by category
     */
    public Map<String, CategoryPerformance> getPerformanceByCategory() {
        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("closedAt").gte(Instant.now().minus(30, ChronoUnit.DAYS))
                        .and("outcome").in(SignalOutcome.Outcome.WIN, SignalOutcome.Outcome.LOSS)),
                Aggregation.group("category")
                        .count().as("totalTrades")
                        .sum("pnlPct").as("totalPnlPct")
                        .avg("pnlPct").as("avgPnlPct")
                        .avg("rMultiple").as("avgRMultiple")
        );

        AggregationResults<CategoryPerformance> results =
                mongoTemplate.aggregate(agg, COLLECTION, CategoryPerformance.class);

        Map<String, CategoryPerformance> performanceMap = new ConcurrentHashMap<>();
        for (CategoryPerformance perf : results.getMappedResults()) {
            if (perf.getCategory() != null) {
                performanceMap.put(perf.getCategory(), perf);
            }
        }
        return performanceMap;
    }

    // ======================== HELPER METHODS ========================

    private void calculateDerivedFields(SignalOutcome outcome) {
        // Calculate P&L percentage
        if (outcome.getActualEntryPrice() > 0 && outcome.getExitPrice() > 0) {
            double pnl;
            if ("LONG".equals(outcome.getDirection())) {
                pnl = (outcome.getExitPrice() - outcome.getActualEntryPrice()) / outcome.getActualEntryPrice() * 100;
            } else {
                pnl = (outcome.getActualEntryPrice() - outcome.getExitPrice()) / outcome.getActualEntryPrice() * 100;
            }
            outcome.setPnlPct(pnl);
        }

        // Calculate R-multiple
        double risk = outcome.getRiskTaken();
        if (risk > 0) {
            double pnlAmount = "LONG".equals(outcome.getDirection()) ?
                    outcome.getExitPrice() - outcome.getActualEntryPrice() :
                    outcome.getActualEntryPrice() - outcome.getExitPrice();
            outcome.setRMultiple(pnlAmount / risk);
        }

        // Calculate time metrics
        if (outcome.getSignalGeneratedAt() != null && outcome.getEnteredAt() != null) {
            outcome.setSignalToEntryMs(
                    outcome.getEnteredAt().toEpochMilli() - outcome.getSignalGeneratedAt().toEpochMilli());
        }
        if (outcome.getEnteredAt() != null && outcome.getClosedAt() != null) {
            outcome.setTimeInTradeMs(
                    outcome.getClosedAt().toEpochMilli() - outcome.getEnteredAt().toEpochMilli());
        }
        if (outcome.getSignalGeneratedAt() != null && outcome.getClosedAt() != null) {
            outcome.setTotalDurationMs(
                    outcome.getClosedAt().toEpochMilli() - outcome.getSignalGeneratedAt().toEpochMilli());
        }

        // Calculate entry slippage
        if (outcome.getPlannedEntryPrice() > 0 && outcome.getActualEntryPrice() > 0) {
            outcome.setEntrySlippage(
                    (outcome.getActualEntryPrice() - outcome.getPlannedEntryPrice()) / outcome.getPlannedEntryPrice() * 100);
        }
    }

    private double calculatePnl(TradingSignal signal, SignalOutcome.TradeExecution execution) {
        if (signal.getDirection() == TradingSignal.Direction.LONG) {
            return (execution.getExitPrice() - execution.getEntryPrice()) / execution.getEntryPrice() * 100;
        } else {
            return (execution.getEntryPrice() - execution.getExitPrice()) / execution.getEntryPrice() * 100;
        }
    }

    private void calculateExcursions(SignalOutcome outcome, TradingSignal signal,
                                      SignalOutcome.TradeExecution execution) {
        double entry = execution.getEntryPrice();

        if (signal.getDirection() == TradingSignal.Direction.LONG) {
            outcome.setMaxFavorableExcursion(execution.getMaxPrice());
            outcome.setMaxAdverseExcursion(execution.getMinPrice());
            outcome.setMfePct((execution.getMaxPrice() - entry) / entry * 100);
            outcome.setMaePct((entry - execution.getMinPrice()) / entry * 100);
        } else {
            outcome.setMaxFavorableExcursion(execution.getMinPrice());
            outcome.setMaxAdverseExcursion(execution.getMaxPrice());
            outcome.setMfePct((entry - execution.getMinPrice()) / entry * 100);
            outcome.setMaePct((execution.getMaxPrice() - entry) / entry * 100);
        }
    }

    private SourcePerformance calculateSourcePerformance(String source) {
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);

        Query query = Query.query(Criteria.where("source").is(source)
                .and("closedAt").gte(cutoff)
                .and("outcome").in(SignalOutcome.Outcome.WIN, SignalOutcome.Outcome.LOSS));

        List<SignalOutcome> outcomes = mongoTemplate.find(query, SignalOutcome.class, COLLECTION);

        if (outcomes.isEmpty()) {
            return SourcePerformance.empty(source);
        }

        long wins = outcomes.stream().filter(SignalOutcome::isProfitable).count();
        double totalPnl = outcomes.stream().mapToDouble(SignalOutcome::getPnlPct).sum();
        double avgRMultiple = outcomes.stream().mapToDouble(SignalOutcome::getRMultiple).average().orElse(0);

        return SourcePerformance.builder()
                .source(source)
                .totalTrades(outcomes.size())
                .wins((int) wins)
                .losses(outcomes.size() - (int) wins)
                .winRate((double) wins / outcomes.size())
                .totalPnlPct(totalPnl)
                .avgPnlPct(totalPnl / outcomes.size())
                .avgRMultiple(avgRMultiple)
                .calculatedAt(Instant.now())
                .build();
    }

    private FamilyPerformance calculateFamilyPerformance(String familyId) {
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);

        Query query = Query.query(Criteria.where("familyId").is(familyId)
                .and("closedAt").gte(cutoff)
                .and("outcome").in(SignalOutcome.Outcome.WIN, SignalOutcome.Outcome.LOSS));

        List<SignalOutcome> outcomes = mongoTemplate.find(query, SignalOutcome.class, COLLECTION);

        if (outcomes.isEmpty()) {
            return FamilyPerformance.empty(familyId);
        }

        long wins = outcomes.stream().filter(SignalOutcome::isProfitable).count();
        double totalPnl = outcomes.stream().mapToDouble(SignalOutcome::getPnlPct).sum();
        double avgRMultiple = outcomes.stream().mapToDouble(SignalOutcome::getRMultiple).average().orElse(0);

        return FamilyPerformance.builder()
                .familyId(familyId)
                .totalTrades(outcomes.size())
                .wins((int) wins)
                .losses(outcomes.size() - (int) wins)
                .winRate((double) wins / outcomes.size())
                .totalPnlPct(totalPnl)
                .avgPnlPct(totalPnl / outcomes.size())
                .avgRMultiple(avgRMultiple)
                .calculatedAt(Instant.now())
                .build();
    }

    // ======================== SCHEDULED TASKS ========================

    /**
     * Refresh performance caches periodically
     */
    @Scheduled(fixedRate = 300_000) // Every 5 minutes
    public void refreshCaches() {
        log.debug("[OUTCOME_STORE] Refreshing performance caches...");
        sourcePerformanceCache.clear();
        familyPerformanceCache.clear();
    }

    // ======================== STATISTICS ========================

    /**
     * Get store statistics
     */
    public StoreStats getStats() {
        long total = totalOutcomesRecorded.get();
        long wins = totalWins.get();
        long losses = totalLosses.get();

        return StoreStats.builder()
                .totalOutcomesRecorded(total)
                .totalWins(wins)
                .totalLosses(losses)
                .overallWinRate(total > 0 ? (double) wins / (wins + losses) : 0)
                .sourcesCached(sourcePerformanceCache.size())
                .familiesCached(familyPerformanceCache.size())
                .build();
    }

    // ======================== MODELS ========================

    @Data
    @Builder
    @AllArgsConstructor
    public static class OverallPerformance {
        private int totalTrades;
        private int wins;
        private int losses;
        private double winRate;
        private double totalPnlPct;
        private double avgPnlPct;
        private double avgWinPct;
        private double avgLossPct;
        private double avgRMultiple;
        private double profitFactor;
        private double target1HitRate;
        private double target2HitRate;
        private int periodDays;
        private Instant calculatedAt;

        public static OverallPerformance empty() {
            return OverallPerformance.builder()
                    .calculatedAt(Instant.now())
                    .build();
        }

        @Override
        public String toString() {
            return String.format("Overall: %d trades, %.1f%% win rate, %.2f%% total P&L, %.2f avg R",
                    totalTrades, winRate * 100, totalPnlPct, avgRMultiple);
        }
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class SourcePerformance {
        private String source;
        private int totalTrades;
        private int wins;
        private int losses;
        private double winRate;
        private double totalPnlPct;
        private double avgPnlPct;
        private double avgRMultiple;
        private Instant calculatedAt;

        public static SourcePerformance empty(String source) {
            return SourcePerformance.builder()
                    .source(source)
                    .calculatedAt(Instant.now())
                    .build();
        }
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class FamilyPerformance {
        private String familyId;
        private int totalTrades;
        private int wins;
        private int losses;
        private double winRate;
        private double totalPnlPct;
        private double avgPnlPct;
        private double avgRMultiple;
        private Instant calculatedAt;

        public static FamilyPerformance empty(String familyId) {
            return FamilyPerformance.builder()
                    .familyId(familyId)
                    .calculatedAt(Instant.now())
                    .build();
        }
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class CategoryPerformance {
        private String category;
        private int totalTrades;
        private double totalPnlPct;
        private double avgPnlPct;
        private double avgRMultiple;
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class StoreStats {
        private long totalOutcomesRecorded;
        private long totalWins;
        private long totalLosses;
        private double overallWinRate;
        private int sourcesCached;
        private int familiesCached;

        @Override
        public String toString() {
            return String.format("OutcomeStore: %d recorded, %d wins, %d losses (%.1f%% win rate)",
                    totalOutcomesRecorded, totalWins, totalLosses, overallWinRate * 100);
        }
    }
}
