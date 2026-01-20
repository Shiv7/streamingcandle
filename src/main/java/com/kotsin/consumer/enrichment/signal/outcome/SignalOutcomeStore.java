package com.kotsin.consumer.enrichment.signal.outcome;

import com.kotsin.consumer.enrichment.signal.learning.FailureContext;
import com.kotsin.consumer.enrichment.signal.learning.FailureLearningStore;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
public class SignalOutcomeStore {

    private final MongoTemplate mongoTemplate;

    // FIX: Inject FailureLearningStore for capturing failure context on SL hits
    @Autowired(required = false)
    private FailureLearningStore failureLearningStore;

    @Autowired
    public SignalOutcomeStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

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
     * Cache of performance stats by setup type (e.g., SCALP_REVERSAL_LONG)
     */
    private final Map<String, SetupPerformance> setupPerformanceCache = new ConcurrentHashMap<>();

    /**
     * FIX: Cache of performance stats by scripCode (per-instrument learning)
     * Key: scripCode, Value: ScripPerformance
     */
    private final Map<String, ScripPerformance> scripPerformanceCache = new ConcurrentHashMap<>();

    /**
     * FIX: Cache of performance stats by scripCode + setupId combination
     * Key: scripCode:setupId, Value: ScripSetupPerformance
     * This enables learning which setups work best for each specific stock
     */
    private final Map<String, ScripSetupPerformance> scripSetupPerformanceCache = new ConcurrentHashMap<>();

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

            log.info("[OUTCOME_STORE] Recorded outcome for signal {}: {} {}%",
                    outcome.getSignalId(), outcome.getOutcome(),
                    String.format("%.2f", outcome.getPnlPct()));

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

            // FIX: Capture failure context for learning
            if (failureLearningStore != null) {
                captureFailureContext(signal, execution, outcome);
            }
        } else if (execution.isTarget2Hit()) {
            outcome.setOutcome(SignalOutcome.Outcome.WIN);
            outcome.setExitReason(SignalOutcome.ExitReason.TARGET_2_HIT);

            // FIX: Record success for balanced learning
            if (failureLearningStore != null) {
                recordSuccessForLearning(signal);
            }
        } else if (execution.isTarget1Hit()) {
            outcome.setOutcome(SignalOutcome.Outcome.WIN);
            outcome.setExitReason(SignalOutcome.ExitReason.TARGET_1_HIT);

            // FIX: Record success for balanced learning
            if (failureLearningStore != null) {
                recordSuccessForLearning(signal);
            }
        } else {
            // Determine by P&L
            double pnl = calculatePnl(signal, execution);
            if (pnl > 0) {
                outcome.setOutcome(SignalOutcome.Outcome.WIN);
                if (failureLearningStore != null) {
                    recordSuccessForLearning(signal);
                }
            } else if (pnl < -0.05) { // 0.05% threshold for breakeven
                outcome.setOutcome(SignalOutcome.Outcome.LOSS);
                // Still a failure, capture it
                if (failureLearningStore != null) {
                    captureFailureContext(signal, execution, outcome);
                }
            } else {
                outcome.setOutcome(SignalOutcome.Outcome.BREAKEVEN);
            }
        }

        // Calculate MFE/MAE
        calculateExcursions(outcome, signal, execution);

        recordOutcome(outcome);
    }

    /**
     * FIX: Capture failure context for learning when SL is hit
     */
    private void captureFailureContext(TradingSignal signal, SignalOutcome.TradeExecution execution,
                                        SignalOutcome outcome) {
        try {
            // Calculate time to SL
            long timeToSLMs = 0;
            if (execution.getEnteredAt() != null && execution.getClosedAt() != null) {
                timeToSLMs = java.time.Duration.between(execution.getEnteredAt(), execution.getClosedAt()).toMillis();
            }

            // Determine failure type based on behavior
            FailureContext.FailureType failureType = classifyFailureType(signal, execution, outcome, timeToSLMs);

            log.info("[OUTCOME_STORE] Capturing failure context | {} {} {} | type={} | timeToSL={}min | MAE={}% MFE={}% | wasProfitable={}",
                    signal.getScripCode(),
                    signal.getDirection(),
                    signal.getSetupId(),
                    failureType,
                    timeToSLMs / 60000,
                    String.format("%.2f", outcome.getMaePct()),
                    String.format("%.2f", outcome.getMfePct()),
                    outcome.getMfePct() > 0.1);

            // Build failure context
            FailureContext failure = FailureContext.builder()
                    .signalId(signal.getSignalId())
                    .familyId(signal.getFamilyId())
                    .scripCode(signal.getScripCode())
                    .companyName(signal.getCompanyName())
                    .direction(signal.getDirection().name())
                    .setupType(signal.getSetupId())
                    .signalSource(signal.getSource() != null ? signal.getSource().name() : null)
                    // Entry context
                    .entryPrice(execution.getEntryPrice())
                    .stopLoss(signal.getStopLoss())
                    .target1(signal.getTarget1())
                    .target2(signal.getTarget2())
                    .entryRiskReward(signal.getRiskRewardRatio())
                    .entryTime(execution.getEnteredAt())
                    // Exit context
                    .exitPrice(execution.getExitPrice())
                    .exitTime(execution.getClosedAt())
                    .timeToSLMillis(timeToSLMs)
                    // Excursions
                    .maxAdverseExcursionPct(outcome.getMaePct())
                    .maxFavorableExcursionPct(outcome.getMfePct())
                    .wasEverProfitable(outcome.getMfePct() > 0.1) // Was profitable by at least 0.1%
                    .maxProfitBeforeReversalPct(outcome.getMfePct())
                    // Classification
                    .failureType(failureType)
                    .build();

            // Record in learning store
            failureLearningStore.recordFailure(failure);

            log.debug("[OUTCOME_STORE] Failure context sent to learning store | signalId={}", signal.getSignalId());

        } catch (Exception e) {
            log.warn("[OUTCOME_STORE] Failed to capture failure context for {}: {}", signal.getSignalId(), e.getMessage());
        }
    }

    /**
     * FIX: Classify failure type based on trade behavior
     */
    private FailureContext.FailureType classifyFailureType(TradingSignal signal,
                                                            SignalOutcome.TradeExecution execution,
                                                            SignalOutcome outcome, long timeToSLMs) {
        // Quick failure (< 5 minutes) and never profitable
        if (timeToSLMs < 5 * 60 * 1000 && outcome.getMfePct() < 0.1) {
            return FailureContext.FailureType.IMMEDIATE_REVERSAL;
        }

        // Was significantly profitable before reversing
        if (outcome.getMfePct() > 0.5) {
            return FailureContext.FailureType.PROFIT_REVERSAL;
        }

        // Very quick failure (< 15 minutes)
        if (timeToSLMs < 15 * 60 * 1000) {
            // Check if in opening period
            if (execution.getEnteredAt() != null) {
                int hour = execution.getEnteredAt().atZone(java.time.ZoneId.of("Asia/Kolkata")).getHour();
                int minute = execution.getEnteredAt().atZone(java.time.ZoneId.of("Asia/Kolkata")).getMinute();
                if (hour == 9 && minute < 45) {
                    return FailureContext.FailureType.OPENING_VOLATILITY;
                }
            }
            return FailureContext.FailureType.IMMEDIATE_REVERSAL;
        }

        // Long time to SL (> 60 minutes) with gradual decline
        if (timeToSLMs > 60 * 60 * 1000 && outcome.getMfePct() < 0.3) {
            return FailureContext.FailureType.GRADUAL_DECAY;
        }

        // Check for EOD failure
        if (execution.getClosedAt() != null) {
            int hour = execution.getClosedAt().atZone(java.time.ZoneId.of("Asia/Kolkata")).getHour();
            if (hour >= 15) {
                return FailureContext.FailureType.EOD_REVERSAL;
            }
        }

        return FailureContext.FailureType.UNKNOWN;
    }

    /**
     * FIX: Record success for balanced learning
     */
    private void recordSuccessForLearning(TradingSignal signal) {
        try {
            int hour = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata")).getHour();

            log.info("[OUTCOME_STORE] Recording success for learning | {} {} {} | hour={}",
                    signal.getScripCode(),
                    signal.getDirection(),
                    signal.getSetupId(),
                    hour);

            failureLearningStore.recordSuccess(
                    signal.getScripCode(),
                    signal.getDirection().name(),
                    signal.getSetupId(),
                    null, // oiInterpretation not available here
                    0.5,  // session position not available here
                    hour
            );
        } catch (Exception e) {
            log.debug("[OUTCOME_STORE] Failed to record success for learning: {}", e.getMessage());
        }
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
     * Get performance stats by setup type (e.g., SCALP_REVERSAL_LONG, SWING_SHORT)
     * This is the key method for calculating actual historicalSuccessRate
     */
    public SetupPerformance getSetupPerformance(String setupId) {
        return setupPerformanceCache.computeIfAbsent(setupId, this::calculateSetupPerformance);
    }

    /**
     * Get historical success rate for a setup type
     * Returns the actual win rate based on recorded outcomes
     *
     * @param setupId Setup ID (e.g., "SCALP_REVERSAL_LONG")
     * @return Win rate between 0.0 and 1.0, or 0.35 (pessimistic) if insufficient data
     */
    private static final double PESSIMISTIC_WIN_RATE = 0.35; // BUG #8 FIX: Below break-even

    public double getHistoricalSuccessRate(String setupId) {
        SetupPerformance perf = getSetupPerformance(setupId);
        if (perf == null || perf.getTotalTrades() < 10) {
            // BUG #8 FIX: Not enough data - return pessimistic 0.35 (assume no edge)
            // Changed from 0.5 (neutral) to discourage unproven setups
            return PESSIMISTIC_WIN_RATE;
        }
        return perf.getWinRate();
    }

    /**
     * Get historical sample count for a setup type
     */
    public int getHistoricalSampleCount(String setupId) {
        SetupPerformance perf = getSetupPerformance(setupId);
        return perf != null ? perf.getTotalTrades() : 0;
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

    // ======================== PER-SCRIP PERFORMANCE (FIX) ========================

    /**
     * FIX: Get performance for a specific scripCode
     * Enables per-instrument learning - each stock behaves differently
     */
    public ScripPerformance getScripPerformance(String scripCode) {
        if (scripCode == null) return ScripPerformance.empty(scripCode);

        return scripPerformanceCache.computeIfAbsent(scripCode, this::calculateScripPerformance);
    }

    /**
     * FIX: Get performance for scripCode + setupId combination
     * The most granular level - which setup works best for which stock
     */
    public ScripSetupPerformance getScripSetupPerformance(String scripCode, String setupId) {
        if (scripCode == null || setupId == null) {
            return ScripSetupPerformance.empty(scripCode, setupId);
        }

        String key = scripCode + ":" + setupId;
        return scripSetupPerformanceCache.computeIfAbsent(key, k ->
                calculateScripSetupPerformance(scripCode, setupId));
    }

    /**
     * FIX: Get win rate for a specific scripCode
     * Returns historical win rate or 0.5 (neutral) if insufficient data
     */
    public double getScripWinRate(String scripCode) {
        ScripPerformance perf = getScripPerformance(scripCode);
        if (perf == null || perf.getTotalTrades() < 5) {
            return 0.5; // Neutral assumption when no data
        }
        return perf.getWinRate();
    }

    /**
     * FIX: Get win rate for scripCode + setupId combination
     * Returns historical win rate or 0.5 (neutral) if insufficient data
     */
    public double getScripSetupWinRate(String scripCode, String setupId) {
        ScripSetupPerformance perf = getScripSetupPerformance(scripCode, setupId);
        if (perf == null || perf.getTotalTrades() < 3) {
            return 0.5; // Neutral assumption when no data
        }
        return perf.getWinRate();
    }

    /**
     * FIX: Check if a scripCode + setupId combination should be avoided
     * Based on historical performance (data-driven, not hardcoded)
     */
    public boolean shouldAvoidScripSetup(String scripCode, String setupId) {
        ScripSetupPerformance perf = getScripSetupPerformance(scripCode, setupId);
        return perf != null && perf.shouldAvoid();
    }

    /**
     * FIX: Check if a scripCode + setupId combination is high-probability
     * Based on historical performance
     */
    public boolean isHighProbabilityScripSetup(String scripCode, String setupId) {
        ScripSetupPerformance perf = getScripSetupPerformance(scripCode, setupId);
        return perf != null && perf.isHighProbability();
    }

    /**
     * FIX: Calculate per-scrip performance from MongoDB
     */
    private ScripPerformance calculateScripPerformance(String scripCode) {
        Instant cutoff = Instant.now().minus(60, ChronoUnit.DAYS); // 60 days for per-scrip

        Query query = Query.query(Criteria.where("scripCode").is(scripCode)
                .and("closedAt").gte(cutoff)
                .and("outcome").in(SignalOutcome.Outcome.WIN, SignalOutcome.Outcome.LOSS));

        List<SignalOutcome> outcomes = mongoTemplate.find(query, SignalOutcome.class, COLLECTION);

        if (outcomes.isEmpty()) {
            return ScripPerformance.empty(scripCode);
        }

        int wins = (int) outcomes.stream().filter(SignalOutcome::isProfitable).count();
        int losses = outcomes.size() - wins;
        double totalPnl = outcomes.stream().mapToDouble(SignalOutcome::getPnlPct).sum();
        double avgPnl = totalPnl / outcomes.size();
        double avgWin = outcomes.stream().filter(o -> o.getPnlPct() > 0).mapToDouble(SignalOutcome::getPnlPct).average().orElse(0);
        double avgLoss = outcomes.stream().filter(o -> o.getPnlPct() < 0).mapToDouble(SignalOutcome::getPnlPct).average().orElse(0);
        double avgRMultiple = outcomes.stream().mapToDouble(SignalOutcome::getRMultiple).average().orElse(0);

        // Calculate expectancy and profit factor
        double winRate = (double) wins / outcomes.size();
        double expectancy = (winRate * avgWin) + ((1 - winRate) * avgLoss);
        double profitFactor = (avgLoss != 0 && losses > 0) ?
                Math.abs(avgWin * wins / (avgLoss * losses)) : 0;

        // Find best and worst setups for this scrip
        Map<String, List<SignalOutcome>> bySetup = outcomes.stream()
                .filter(o -> o.getSetupId() != null)
                .collect(java.util.stream.Collectors.groupingBy(SignalOutcome::getSetupId));

        String bestSetup = null;
        String worstSetup = null;
        double bestWinRate = 0;
        double worstWinRate = 1;

        for (Map.Entry<String, List<SignalOutcome>> entry : bySetup.entrySet()) {
            if (entry.getValue().size() >= 3) { // At least 3 trades for this setup
                double setupWins = entry.getValue().stream().filter(SignalOutcome::isProfitable).count();
                double setupWinRate = setupWins / entry.getValue().size();
                if (setupWinRate > bestWinRate) {
                    bestWinRate = setupWinRate;
                    bestSetup = entry.getKey();
                }
                if (setupWinRate < worstWinRate) {
                    worstWinRate = setupWinRate;
                    worstSetup = entry.getKey();
                }
            }
        }

        log.info("[SCRIP_PERF] {} | {} trades | {}% win rate | best={} worst={} | expectancy={}%",
                scripCode, outcomes.size(), String.format("%.1f", winRate * 100), bestSetup, worstSetup, String.format("%.2f", expectancy));

        return ScripPerformance.builder()
                .scripCode(scripCode)
                .totalTrades(outcomes.size())
                .wins(wins)
                .losses(losses)
                .winRate(winRate)
                .totalPnlPct(totalPnl)
                .avgPnlPct(avgPnl)
                .avgWinPct(avgWin)
                .avgLossPct(avgLoss)
                .avgRMultiple(avgRMultiple)
                .expectancy(expectancy)
                .profitFactor(profitFactor)
                .bestSetup(bestSetup)
                .worstSetup(worstSetup)
                .calculatedAt(Instant.now())
                .build();
    }

    /**
     * FIX: Calculate scrip + setup specific performance
     */
    private ScripSetupPerformance calculateScripSetupPerformance(String scripCode, String setupId) {
        Instant cutoff = Instant.now().minus(60, ChronoUnit.DAYS);

        Query query = Query.query(Criteria.where("scripCode").is(scripCode)
                .and("setupId").is(setupId)
                .and("closedAt").gte(cutoff)
                .and("outcome").in(SignalOutcome.Outcome.WIN, SignalOutcome.Outcome.LOSS));

        List<SignalOutcome> outcomes = mongoTemplate.find(query, SignalOutcome.class, COLLECTION);

        if (outcomes.isEmpty()) {
            return ScripSetupPerformance.empty(scripCode, setupId);
        }

        int wins = (int) outcomes.stream().filter(SignalOutcome::isProfitable).count();
        int losses = outcomes.size() - wins;
        double winRate = (double) wins / outcomes.size();
        double avgPnl = outcomes.stream().mapToDouble(SignalOutcome::getPnlPct).average().orElse(0);
        double avgRMultiple = outcomes.stream().mapToDouble(SignalOutcome::getRMultiple).average().orElse(0);
        double avgWin = outcomes.stream().filter(o -> o.getPnlPct() > 0).mapToDouble(SignalOutcome::getPnlPct).average().orElse(0);
        double avgLoss = outcomes.stream().filter(o -> o.getPnlPct() < 0).mapToDouble(SignalOutcome::getPnlPct).average().orElse(0);
        double expectancy = (winRate * avgWin) + ((1 - winRate) * avgLoss);

        log.debug("[SCRIP_SETUP_PERF] {}:{} | {} trades | {}% win rate | expectancy={}%",
                scripCode, setupId, outcomes.size(), String.format("%.1f", winRate * 100), String.format("%.2f", expectancy));

        return ScripSetupPerformance.builder()
                .scripCode(scripCode)
                .setupId(setupId)
                .totalTrades(outcomes.size())
                .wins(wins)
                .losses(losses)
                .winRate(winRate)
                .avgPnlPct(avgPnl)
                .avgRMultiple(avgRMultiple)
                .expectancy(expectancy)
                .calculatedAt(Instant.now())
                .build();
    }

    /**
     * FIX: Invalidate scrip performance cache when new outcome is recorded
     */
    private void invalidateScripCache(SignalOutcome outcome) {
        if (outcome.getScripCode() != null) {
            scripPerformanceCache.remove(outcome.getScripCode());
            // Also invalidate scrip+setup cache
            if (outcome.getSetupId() != null) {
                scripSetupPerformanceCache.remove(outcome.getScripCode() + ":" + outcome.getSetupId());
            }
        }
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

    /**
     * Calculate performance for a specific setup type
     * This is used to get actual historical success rate for signals
     */
    private SetupPerformance calculateSetupPerformance(String setupId) {
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);

        Query query = Query.query(Criteria.where("setupId").is(setupId)
                .and("closedAt").gte(cutoff)
                .and("outcome").in(SignalOutcome.Outcome.WIN, SignalOutcome.Outcome.LOSS));

        List<SignalOutcome> outcomes = mongoTemplate.find(query, SignalOutcome.class, COLLECTION);

        if (outcomes.isEmpty()) {
            return SetupPerformance.empty(setupId);
        }

        long wins = outcomes.stream().filter(SignalOutcome::isProfitable).count();
        long losses = outcomes.size() - wins;
        double totalPnl = outcomes.stream().mapToDouble(SignalOutcome::getPnlPct).sum();
        double avgWin = outcomes.stream().filter(o -> o.getPnlPct() > 0).mapToDouble(SignalOutcome::getPnlPct).average().orElse(0);
        double avgLoss = outcomes.stream().filter(o -> o.getPnlPct() < 0).mapToDouble(SignalOutcome::getPnlPct).average().orElse(0);
        double avgRMultiple = outcomes.stream().mapToDouble(SignalOutcome::getRMultiple).average().orElse(0);

        // Calculate expectancy: (winRate * avgWin) + ((1-winRate) * avgLoss)
        double winRate = (double) wins / outcomes.size();
        double expectancy = (winRate * avgWin) + ((1 - winRate) * avgLoss);

        // Calculate profit factor: |grossProfit / grossLoss|
        double grossProfit = outcomes.stream().filter(o -> o.getPnlPct() > 0).mapToDouble(SignalOutcome::getPnlPct).sum();
        double grossLoss = Math.abs(outcomes.stream().filter(o -> o.getPnlPct() < 0).mapToDouble(SignalOutcome::getPnlPct).sum());
        double profitFactor = grossLoss > 0 ? grossProfit / grossLoss : 0;

        return SetupPerformance.builder()
                .setupId(setupId)
                .totalTrades(outcomes.size())
                .wins((int) wins)
                .losses((int) losses)
                .winRate(winRate)
                .totalPnlPct(totalPnl)
                .avgPnlPct(totalPnl / outcomes.size())
                .avgWinPct(avgWin)
                .avgLossPct(avgLoss)
                .avgRMultiple(avgRMultiple)
                .expectancy(expectancy)
                .profitFactor(profitFactor)
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
        setupPerformanceCache.clear();
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

    /**
     * Performance statistics for a specific setup type (e.g., SCALP_REVERSAL_LONG)
     * Used for calculating actual historicalSuccessRate in signals
     */
    @Data
    @Builder
    @AllArgsConstructor
    public static class SetupPerformance {
        private String setupId;
        private int totalTrades;
        private int wins;
        private int losses;
        private double winRate;           // Wins / Total
        private double totalPnlPct;
        private double avgPnlPct;
        private double avgWinPct;         // Average winning trade %
        private double avgLossPct;        // Average losing trade % (negative)
        private double avgRMultiple;
        private double expectancy;        // Expected value per trade
        private double profitFactor;      // Gross profit / Gross loss
        private Instant calculatedAt;

        public static SetupPerformance empty(String setupId) {
            return SetupPerformance.builder()
                    .setupId(setupId)
                    .totalTrades(0)
                    .wins(0)
                    .losses(0)
                    .winRate(0.5)  // Neutral assumption when no data
                    .calculatedAt(Instant.now())
                    .build();
        }

        /**
         * Check if we have sufficient data for statistical significance
         * Minimum 30 trades for reliable stats
         */
        public boolean hasSignificantData() {
            return totalTrades >= 30;
        }

        /**
         * Check if this setup has proven edge (positive expectancy + good sample)
         */
        public boolean hasProvenEdge() {
            return hasSignificantData() && expectancy > 0 && profitFactor > 1.0;
        }

        @Override
        public String toString() {
            return String.format("Setup[%s]: %d trades, %.1f%% win rate, %.2f expectancy, %.2f PF",
                    setupId, totalTrades, winRate * 100, expectancy, profitFactor);
        }
    }

    /**
     * FIX: Performance statistics for a specific scripCode (per-instrument learning)
     * Each stock has its own trading characteristics - learn from its history
     */
    @Data
    @Builder
    @AllArgsConstructor
    public static class ScripPerformance {
        private String scripCode;
        private String companyName;
        private int totalTrades;
        private int wins;
        private int losses;
        private double winRate;
        private double totalPnlPct;
        private double avgPnlPct;
        private double avgWinPct;
        private double avgLossPct;
        private double avgRMultiple;
        private double expectancy;
        private double profitFactor;
        private String bestSetup;      // Which setup works best for this scrip
        private String worstSetup;     // Which setup works worst for this scrip
        private double avgVolatility;  // Average ATR% for this scrip
        private Instant calculatedAt;

        public static ScripPerformance empty(String scripCode) {
            return ScripPerformance.builder()
                    .scripCode(scripCode)
                    .totalTrades(0)
                    .winRate(0.5)
                    .calculatedAt(Instant.now())
                    .build();
        }

        public boolean hasSignificantData() {
            return totalTrades >= 10;
        }

        public boolean hasProvenEdge() {
            return hasSignificantData() && expectancy > 0 && profitFactor > 1.0;
        }
    }

    /**
     * FIX: Performance for specific scripCode + setupId combination
     * This is the most granular level - which setup works best for which stock
     */
    @Data
    @Builder
    @AllArgsConstructor
    public static class ScripSetupPerformance {
        private String scripCode;
        private String setupId;
        private int totalTrades;
        private int wins;
        private int losses;
        private double winRate;
        private double avgPnlPct;
        private double avgRMultiple;
        private double expectancy;
        private Instant calculatedAt;

        public static ScripSetupPerformance empty(String scripCode, String setupId) {
            return ScripSetupPerformance.builder()
                    .scripCode(scripCode)
                    .setupId(setupId)
                    .totalTrades(0)
                    .winRate(0.5)
                    .calculatedAt(Instant.now())
                    .build();
        }

        public boolean hasSignificantData() {
            return totalTrades >= 5; // Lower threshold for scrip-specific
        }

        /**
         * Check if this scrip+setup combination should be avoided
         * Based on historical performance
         */
        public boolean shouldAvoid() {
            return hasSignificantData() && winRate < 0.30;
        }

        /**
         * Check if this is a high-probability combination
         */
        public boolean isHighProbability() {
            return hasSignificantData() && winRate > 0.55 && avgRMultiple > 1.0;
        }
    }
}
