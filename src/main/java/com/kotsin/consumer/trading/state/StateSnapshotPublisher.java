package com.kotsin.consumer.trading.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.config.KafkaTopics;
import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore;
import com.kotsin.consumer.trading.model.Position;
import com.kotsin.consumer.trading.state.dto.*;
import com.kotsin.consumer.trading.strategy.TradingStrategy;
import com.kotsin.consumer.trading.strategy.TradingStrategy.SetupContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * StateSnapshotPublisher - Publishes instrument state data to Kafka for dashboard consumption.
 *
 * Publishes to:
 * - instrument-state-snapshots: Full state snapshots for each instrument
 * - instrument-condition-checks: Detailed condition status for WATCHING instruments
 * - instrument-state-transitions: State change events
 * - strategy-opportunities: Near-opportunities ranked feed
 */
@Slf4j
@Service
public class StateSnapshotPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final List<TradingStrategy> strategies;

    // Lazy injection to break circular dependency
    private InstrumentStateManager stateManager;

    public StateSnapshotPublisher(
            @Qualifier("stringKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            List<TradingStrategy> strategies) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.strategies = strategies;
    }

    // Setter injection to break circular dependency
    @Autowired
    public void setStateManager(@Lazy InstrumentStateManager stateManager) {
        this.stateManager = stateManager;
    }

    // Statistics
    private final AtomicLong snapshotsPublished = new AtomicLong(0);
    private final AtomicLong transitionsPublished = new AtomicLong(0);
    private final AtomicLong opportunitiesPublished = new AtomicLong(0);

    // Cache for detecting state transitions - MUST be thread-safe
    private final Map<String, InstrumentState> lastKnownStates = new ConcurrentHashMap<>();

    // ======================== SNAPSHOT PUBLISHING ========================

    /**
     * Publish snapshot after processing a candle.
     * Called from InstrumentStateManager after each candle processing.
     */
    public void publishSnapshot(String scripCode, EnrichedQuantScore score) {
        try {
            InstrumentStateSnapshot snapshot = buildSnapshot(scripCode, score);
            String payload = objectMapper.writeValueAsString(snapshot);

            kafkaTemplate.send(KafkaTopics.STATE_MACHINE_SNAPSHOTS, scripCode, payload);
            snapshotsPublished.incrementAndGet();

            // Check for state transition
            InstrumentState previousState = lastKnownStates.get(scripCode);
            if (previousState != null && previousState != snapshot.getState()) {
                publishTransition(scripCode, previousState, snapshot.getState(),
                        "State changed during candle processing");
            }
            lastKnownStates.put(scripCode, snapshot.getState());

            // Publish condition checks for WATCHING instruments
            if (snapshot.getState() == InstrumentState.WATCHING && snapshot.getActiveSetups() != null) {
                for (ActiveSetupInfo setup : snapshot.getActiveSetups()) {
                    publishConditionChecks(scripCode, setup.getStrategyId(), setup.getConditions());
                }
            }

            log.debug("[STATE_PUB] Published snapshot for {} | state={} | activeSetups={}",
                    scripCode, snapshot.getState(),
                    snapshot.getActiveSetups() != null ? snapshot.getActiveSetups().size() : 0);

        } catch (JsonProcessingException e) {
            log.error("[STATE_PUB] Failed to serialize snapshot for {}: {}", scripCode, e.getMessage());
        }
    }

    /**
     * Publish a state transition event.
     */
    public void publishTransition(String scripCode, InstrumentState from, InstrumentState to, String reason) {
        try {
            StateTransition transition = StateTransition.builder()
                    .scripCode(scripCode)
                    .fromState(from)
                    .toState(to)
                    .reason(reason)
                    .timestamp(System.currentTimeMillis())
                    .build();

            String payload = objectMapper.writeValueAsString(transition);
            kafkaTemplate.send(KafkaTopics.STATE_MACHINE_TRANSITIONS, scripCode, payload);
            transitionsPublished.incrementAndGet();

            log.info("[STATE_PUB] Published transition {} → {} for {} | reason={}",
                    from, to, scripCode, reason);

        } catch (JsonProcessingException e) {
            log.error("[STATE_PUB] Failed to publish transition for {}: {}", scripCode, e.getMessage());
        }
    }

    /**
     * Publish condition check details for dashboard visualization.
     */
    public void publishConditionChecks(String scripCode, String strategyId, List<ConditionCheck> conditions) {
        if (conditions == null || conditions.isEmpty()) return;

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("scripCode", scripCode);
            payload.put("strategyId", strategyId);
            payload.put("conditions", conditions);
            payload.put("timestamp", System.currentTimeMillis());

            String json = objectMapper.writeValueAsString(payload);
            kafkaTemplate.send(KafkaTopics.STATE_MACHINE_CONDITIONS, scripCode, json);

        } catch (JsonProcessingException e) {
            log.error("[STATE_PUB] Failed to publish conditions for {}: {}", scripCode, e.getMessage());
        }
    }

    /**
     * Publish near-opportunities feed (scheduled).
     */
    @Scheduled(fixedRate = 5000) // Every 5 seconds
    public void publishOpportunities() {
        try {
            List<StrategyOpportunity> opportunities = stateManager.getNearOpportunities();

            if (!opportunities.isEmpty()) {
                String payload = objectMapper.writeValueAsString(opportunities);
                kafkaTemplate.send(KafkaTopics.STATE_MACHINE_OPPORTUNITIES, "all", payload);
                opportunitiesPublished.incrementAndGet();

                log.debug("[STATE_PUB] Published {} near-opportunities", opportunities.size());
            }

        } catch (JsonProcessingException e) {
            log.error("[STATE_PUB] Failed to publish opportunities: {}", e.getMessage());
        }
    }

    // ======================== SNAPSHOT BUILDING ========================

    /**
     * Build a complete snapshot for an instrument.
     */
    private InstrumentStateSnapshot buildSnapshot(String scripCode, EnrichedQuantScore score) {
        InstrumentState state = stateManager.getState(scripCode);
        Position position = stateManager.getPosition(scripCode);

        // Get market data from score
        var tech = score.getTechnicalContext();
        double ofiZscore = 0;
        if (score.getHistoricalContext() != null && score.getHistoricalContext().getOfiContext() != null) {
            ofiZscore = score.getHistoricalContext().getOfiContext().getZscore();
        }

        double vpin = 0;
        if (score.getHistoricalContext() != null && score.getHistoricalContext().getVpinContext() != null) {
            vpin = score.getHistoricalContext().getVpinContext().getCurrentValue();
        }

        // Build active setups for WATCHING state
        List<ActiveSetupInfo> activeSetups = null;
        if (state == InstrumentState.WATCHING) {
            activeSetups = buildActiveSetups(scripCode, score);
        }

        // Build position info for POSITIONED state
        PositionInfo positionInfo = null;
        if (state == InstrumentState.POSITIONED && position != null) {
            positionInfo = buildPositionInfo(position);
        }

        return InstrumentStateSnapshot.builder()
                .scripCode(scripCode)
                .companyName(score.getCompanyName() != null ? score.getCompanyName() : scripCode)
                .state(state)
                .stateTimestamp(System.currentTimeMillis())
                .currentPrice(score.getClose())
                .ofiZscore(ofiZscore)
                .atr(tech != null ? tech.getAtr() : 0)
                .vpin(vpin)
                .superTrendBullish(tech != null && tech.isSuperTrendBullish())
                .superTrendFlip(tech != null && tech.isSuperTrendFlip())
                .bbPercentB(tech != null ? tech.getBbPercentB() : 0.5)
                .bbSqueezing(tech != null && tech.isBbSqueezing())
                .activeSetups(activeSetups)
                .position(positionInfo)
                .signalsToday(stateManager.getSignalsTodayForInstrument(scripCode))
                .maxSignalsPerDay(stateManager.getMaxSignalsPerDay())
                .build();
    }

    /**
     * Build active setups info for WATCHING instruments.
     */
    private List<ActiveSetupInfo> buildActiveSetups(String scripCode, EnrichedQuantScore score) {
        Map<String, SetupContext> setups = stateManager.getWatchingSetups(scripCode);
        if (setups == null || setups.isEmpty()) {
            return Collections.emptyList();
        }

        List<ActiveSetupInfo> result = new ArrayList<>();

        for (Map.Entry<String, SetupContext> entry : setups.entrySet()) {
            String strategyId = entry.getKey();
            SetupContext setup = entry.getValue();

            // Find the strategy to build conditions
            TradingStrategy strategy = strategies.stream()
                    .filter(s -> s.getStrategyId().equals(strategyId))
                    .findFirst()
                    .orElse(null);

            List<ConditionCheck> conditions = buildConditionChecks(strategy, setup, score);
            int progressPercent = calculateProgress(conditions);
            String blockingCondition = findBlockingCondition(conditions);

            ActiveSetupInfo info = ActiveSetupInfo.builder()
                    .strategyId(strategyId)
                    .setupDescription(setup.getSetupDescription())
                    .direction(setup.getDirection() != null ? setup.getDirection().name() : "LONG")
                    .keyLevel(setup.getKeyLevel())
                    .watchingStartTime(setup.getWatchingStartTime())
                    .watchingDurationMs(System.currentTimeMillis() - setup.getWatchingStartTime())
                    .conditions(conditions)
                    .progressPercent(progressPercent)
                    .blockingCondition(blockingCondition)
                    .build();

            result.add(info);
        }

        return result;
    }

    /**
     * Build condition checks for a setup.
     */
    private List<ConditionCheck> buildConditionChecks(TradingStrategy strategy, SetupContext setup, EnrichedQuantScore score) {
        List<ConditionCheck> conditions = new ArrayList<>();

        if (strategy == null || setup == null) {
            return conditions;
        }

        var tech = score.getTechnicalContext();
        double currentPrice = score.getClose();
        double keyLevel = setup.getKeyLevel();

        // Condition 1: At Level (price within 0.5% of key level)
        double distanceToLevel = Math.abs(currentPrice - keyLevel) / keyLevel * 100;
        boolean atLevel = distanceToLevel <= 0.5;
        conditions.add(ConditionCheck.builder()
                .conditionName("At Level")
                .passed(atLevel)
                .currentValue(distanceToLevel)
                .requiredValue(0.5)
                .comparison("<=")
                .progressPercent(atLevel ? 100 : Math.min(99, (int) ((0.5 / Math.max(distanceToLevel, 0.01)) * 100)))
                .displayValue(String.format("%.2f%% (need <=0.5%%)", distanceToLevel))
                .build());

        // Condition 2: OFI Aligned
        double ofiZscore = 0;
        if (score.getHistoricalContext() != null && score.getHistoricalContext().getOfiContext() != null) {
            ofiZscore = score.getHistoricalContext().getOfiContext().getZscore();
        }
        boolean isShort = setup.getDirection() != null &&
                setup.getDirection().name().equals("SHORT");
        boolean ofiAligned = isShort ? ofiZscore < -0.5 : ofiZscore > 0.5;
        double ofiRequired = isShort ? -0.5 : 0.5;
        conditions.add(ConditionCheck.builder()
                .conditionName("OFI Aligned")
                .passed(ofiAligned)
                .currentValue(ofiZscore)
                .requiredValue(ofiRequired)
                .comparison(isShort ? "<" : ">")
                .progressPercent(ofiAligned ? 100 : (int) Math.min(99, Math.abs(ofiZscore / ofiRequired) * 100))
                .displayValue(String.format("%.2f (%s %.1f)", ofiZscore, isShort ? "<" : ">", ofiRequired))
                .build());

        // Condition 3: SuperTrend Aligned
        boolean stAligned = tech != null && (isShort ? !tech.isSuperTrendBullish() : tech.isSuperTrendBullish());
        conditions.add(ConditionCheck.builder()
                .conditionName("ST Aligned")
                .passed(stAligned)
                .currentValue(tech != null && tech.isSuperTrendBullish() ? 1 : 0)
                .requiredValue(isShort ? 0 : 1)
                .comparison("==")
                .progressPercent(stAligned ? 100 : 0)
                .displayValue(stAligned ? "Aligned" : "Not aligned")
                .build());

        // Condition 4: R:R ratio
        double proposedStop = setup.getProposedStop();
        double proposedTarget = setup.getProposedTarget1();
        double risk = Math.abs(currentPrice - proposedStop);
        double reward = Math.abs(proposedTarget - currentPrice);
        double rr = risk > 0 ? reward / risk : 0;
        boolean rrGood = rr >= 1.5;
        conditions.add(ConditionCheck.builder()
                .conditionName("R:R >= 1.5")
                .passed(rrGood)
                .currentValue(rr)
                .requiredValue(1.5)
                .comparison(">=")
                .progressPercent(rrGood ? 100 : (int) Math.min(99, (rr / 1.5) * 100))
                .displayValue(String.format("%.2f (need 1.5)", rr))
                .build());

        // Condition 5: VPIN OK (not too high - below 0.7)
        double vpin = 0;
        if (score.getHistoricalContext() != null && score.getHistoricalContext().getVpinContext() != null) {
            vpin = score.getHistoricalContext().getVpinContext().getCurrentValue();
        }
        boolean vpinOk = vpin < 0.7;
        conditions.add(ConditionCheck.builder()
                .conditionName("VPIN OK")
                .passed(vpinOk)
                .currentValue(vpin)
                .requiredValue(0.7)
                .comparison("<")
                .progressPercent(vpinOk ? 100 : (int) Math.max(0, (1 - (vpin - 0.7) / 0.3) * 100))
                .displayValue(String.format("%.2f (need <0.7)", vpin))
                .build());

        return conditions;
    }

    /**
     * Calculate overall progress based on conditions.
     */
    private int calculateProgress(List<ConditionCheck> conditions) {
        if (conditions == null || conditions.isEmpty()) return 0;

        int totalProgress = conditions.stream()
                .mapToInt(ConditionCheck::getProgressPercent)
                .sum();

        return totalProgress / conditions.size();
    }

    /**
     * Find the first failing condition (blocking condition).
     */
    private String findBlockingCondition(List<ConditionCheck> conditions) {
        if (conditions == null) return null;

        return conditions.stream()
                .filter(c -> !c.isPassed())
                .findFirst()
                .map(ConditionCheck::getConditionName)
                .orElse(null);
    }

    /**
     * Build position info from Position object.
     */
    private PositionInfo buildPositionInfo(Position position) {
        // Null-safe entry time handling
        long entryTimeMs = position.getEntryTime() != null ?
                position.getEntryTime().toEpochMilli() : System.currentTimeMillis();

        return PositionInfo.builder()
                .strategyId(position.getStrategyId())
                .direction(position.getDirection() != null ? position.getDirection().name() : "LONG")
                .entryPrice(position.getEntryPrice())
                .currentPrice(position.getCurrentPrice())
                .stopLoss(position.getCurrentStopLoss())  // Fixed: was getStopLoss()
                .target1(position.getTarget1())
                .target2(position.getTarget2())
                .unrealizedPnL(position.getUnrealizedPnL())
                .pnlPercent(position.getPnLPercent())
                .quantity(position.getQuantity())
                .entryTime(entryTimeMs)
                .durationMs(System.currentTimeMillis() - entryTimeMs)
                .target1Hit(position.isTarget1Hit())
                .build();
    }

    // ======================== STATISTICS ========================

    public String getStats() {
        return String.format("[STATE_PUB] snapshots=%d | transitions=%d | opportunities=%d",
                snapshotsPublished.get(), transitionsPublished.get(), opportunitiesPublished.get());
    }

    public void resetStats() {
        snapshotsPublished.set(0);
        transitionsPublished.set(0);
        opportunitiesPublished.set(0);
    }
}
