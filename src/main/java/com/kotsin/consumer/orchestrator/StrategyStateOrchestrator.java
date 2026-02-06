package com.kotsin.consumer.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.gate.GateChain;
import com.kotsin.consumer.gate.model.GateResult.ChainResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StrategyStateOrchestrator - The missing bridge between signal triggers and the state machine.
 *
 * CONSUMES:
 *   - kotsin_FUDKII (FUDKII triggers)
 *   - pivot-confluence-signals (Pivot Confluence triggers)
 *   - microalpha-signals (MicroAlpha triggers)
 *
 * PRODUCES:
 *   - instrument-state-snapshots (instrument lifecycle states for dashboard)
 *   - strategy-opportunities (near-signal instruments for dashboard)
 *
 * STATE MACHINE:
 *   IDLE -> WATCHING -> GATE_CHECK -> ACTIVE -> POSITIONED -> COOLDOWN -> IDLE
 *
 * CONFLICT RESOLUTION:
 *   When FUDKII and Pivot disagree on direction for the same instrument,
 *   the signal is flagged as CONFLICTED and NOT promoted to ACTIVE.
 */
@Component
@ConditionalOnProperty(name = "orchestrator.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class StrategyStateOrchestrator {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private GateChain gateChain;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String STATE_SNAPSHOTS_TOPIC = "instrument-state-snapshots";
    private static final String OPPORTUNITIES_TOPIC = "strategy-opportunities";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // NSE market hours
    private static final int NSE_OPEN_HOUR = 9, NSE_OPEN_MIN = 15;
    private static final int NSE_CLOSE_HOUR = 15, NSE_CLOSE_MIN = 30;

    @Value("${orchestrator.signal.ttl.minutes:30}")
    private int signalTtlMinutes;

    @Value("${orchestrator.cooldown.minutes:15}")
    private int cooldownMinutes;

    @Value("${orchestrator.max.signals.per.day:5}")
    private int maxSignalsPerDay;

    @Value("${orchestrator.gate.min.score:50}")
    private double gateMinScore;

    @Value("${orchestrator.state.management.enabled:false}")
    private boolean stateManagementEnabled;

    // Per-instrument state tracking
    private final Map<String, InstrumentState> instrumentStates = new ConcurrentHashMap<>();

    // Signal direction tracking for conflict detection
    private final Map<String, SignalDirection> fudkiiDirections = new ConcurrentHashMap<>();
    private final Map<String, SignalDirection> pivotDirections = new ConcurrentHashMap<>();
    private final Map<String, SignalDirection> microAlphaDirections = new ConcurrentHashMap<>();

    // Daily signal counters
    private final Map<String, Integer> dailySignalCount = new ConcurrentHashMap<>();
    private volatile LocalDate currentTradeDate = LocalDate.now(IST);

    @PostConstruct
    public void init() {
        if (stateManagementEnabled) {
            log.info("StrategyStateOrchestrator initialized | TTL={}min, cooldown={}min, maxPerDay={}, gateMinScore={}",
                    signalTtlMinutes, cooldownMinutes, maxSignalsPerDay, gateMinScore);
        } else {
            log.info("StrategyStateOrchestrator in MONITORING MODE — state management disabled, signal consumption only");
        }
    }

    // ==================== KAFKA CONSUMERS ====================

    @KafkaListener(
            topics = "kotsin_FUDKII",
            groupId = "${orchestrator.consumer.group:strategy-orchestrator-v1}"
    )
    public void onFudkiiSignal(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String scripCode = root.path("scripCode").asText();
            if (scripCode == null || scripCode.isEmpty()) return;

            boolean triggered = root.path("triggered").asBoolean(false);
            String direction = root.path("direction").asText("NONE");
            double score = root.path("triggerScore").asDouble(0);
            double triggerPrice = root.path("triggerPrice").asDouble(0);

            if (triggered) {
                fudkiiDirections.put(scripCode, new SignalDirection(direction, score, Instant.now()));

                processSignalTrigger(scripCode, "FUDKII", direction, score, triggerPrice, root);
            } else {
                fudkiiDirections.remove(scripCode);

                // If instrument was WATCHING due to FUDKII and trigger cancelled, re-evaluate
                InstrumentState state = instrumentStates.get(scripCode);
                if (state != null && "WATCHING".equals(state.state) && "FUDKII".equals(state.primaryStrategy)) {
                    transitionTo(scripCode, "IDLE", null);
                }
            }
        } catch (Exception e) {
            log.error("Orchestrator error processing FUDKII: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(
            topics = "pivot-confluence-signals",
            groupId = "${orchestrator.consumer.group:strategy-orchestrator-v1}"
    )
    public void onPivotSignal(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String scripCode = root.path("scripCode").asText();
            if (scripCode == null || scripCode.isEmpty()) return;

            boolean triggered = root.path("triggered").asBoolean(false);
            String direction = root.path("direction").asText("NEUTRAL");
            double score = root.path("score").asDouble(0);
            double entryPrice = root.path("entryPrice").asDouble(0);

            if (triggered) {
                pivotDirections.put(scripCode, new SignalDirection(direction, score, Instant.now()));

                processSignalTrigger(scripCode, "PIVOT", direction, score, entryPrice, root);
            } else {
                pivotDirections.remove(scripCode);

                InstrumentState state = instrumentStates.get(scripCode);
                if (state != null && "WATCHING".equals(state.state) && "PIVOT".equals(state.primaryStrategy)) {
                    transitionTo(scripCode, "IDLE", null);
                }
            }
        } catch (Exception e) {
            log.error("Orchestrator error processing Pivot: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(
            topics = "microalpha-signals",
            groupId = "${orchestrator.consumer.group:strategy-orchestrator-v1}"
    )
    public void onMicroAlphaSignal(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String scripCode = root.path("scripCode").asText();
            if (scripCode == null || scripCode.isEmpty()) return;

            boolean triggered = root.path("triggered").asBoolean(false);
            String direction = root.path("direction").asText("NONE");
            double score = root.path("score").asDouble(0);
            double entryPrice = root.path("entryPrice").asDouble(0);

            if (triggered) {
                microAlphaDirections.put(scripCode, new SignalDirection(direction, score, Instant.now()));

                processSignalTrigger(scripCode, "MICROALPHA", direction, score, entryPrice, root);
            } else {
                microAlphaDirections.remove(scripCode);

                InstrumentState state = instrumentStates.get(scripCode);
                if (state != null && "WATCHING".equals(state.state) && "MICROALPHA".equals(state.primaryStrategy)) {
                    transitionTo(scripCode, "IDLE", null);
                }
            }
        } catch (Exception e) {
            log.error("Orchestrator error processing MicroAlpha: {}", e.getMessage(), e);
        }
    }

    // ==================== CORE STATE MACHINE LOGIC ====================

    private void processSignalTrigger(String scripCode, String strategy, String direction,
                                       double score, double price, JsonNode rawSignal) {

        // When state management is disabled, only monitor (track directions + publish opportunities)
        if (!stateManagementEnabled) {
            log.debug("[ORCHESTRATOR] Monitoring: {} {} {} score={} price={}",
                scripCode, strategy, direction, String.format("%.1f", score), String.format("%.2f", price));
            publishOpportunity(scripCode, strategy, direction, score, price, "Monitoring mode", null);
            return;
        }

        // --- MARKET HOURS CHECK ---
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(LocalTime.of(NSE_OPEN_HOUR, NSE_OPEN_MIN)) ||
                now.isAfter(LocalTime.of(NSE_CLOSE_HOUR, NSE_CLOSE_MIN))) {
            log.debug("[ORCHESTRATOR] {} outside market hours, skipping", scripCode);
            return;
        }

        // --- DAILY CAP CHECK ---
        resetDailyCounterIfNeeded();
        String dailyKey = scripCode + "|" + currentTradeDate;
        int todayCount = dailySignalCount.getOrDefault(dailyKey, 0);
        if (todayCount >= maxSignalsPerDay) {
            log.warn("[ORCHESTRATOR] {} daily cap reached ({}/{})", scripCode, todayCount, maxSignalsPerDay);
            return;
        }

        // --- COOLDOWN CHECK ---
        InstrumentState currentState = instrumentStates.get(scripCode);
        if (currentState != null && "COOLDOWN".equals(currentState.state)) {
            long cooldownRemaining = currentState.stateEntryTime +
                    (cooldownMinutes * 60_000L) - System.currentTimeMillis();
            if (cooldownRemaining > 0) {
                log.debug("[ORCHESTRATOR] {} in cooldown, {}s remaining", scripCode, cooldownRemaining / 1000);
                publishOpportunity(scripCode, strategy, direction, score, price,
                        "Cooldown active", String.format("%ds remaining", cooldownRemaining / 1000));
                return;
            }
            // Cooldown expired, clear it
            transitionTo(scripCode, "IDLE", null);
        }

        // --- CONFLICT DETECTION ---
        String conflictStatus = checkConflict(scripCode, strategy, direction);
        if ("CONFLICTED".equals(conflictStatus)) {
            log.warn("[ORCHESTRATOR] CONFLICT: {} has opposing FUDKII ({}) and Pivot ({}) directions",
                    scripCode,
                    fudkiiDirections.containsKey(scripCode) ? fudkiiDirections.get(scripCode).direction : "none",
                    pivotDirections.containsKey(scripCode) ? pivotDirections.get(scripCode).direction : "none");

            // Publish as opportunity with conflict warning, but do NOT promote to ACTIVE
            publishOpportunity(scripCode, strategy, direction, score * 0.5, price,
                    "SIGNAL CONFLICT: FUDKII vs Pivot disagree", "Wait for alignment");

            // Stay in WATCHING state with conflict flag
            transitionTo(scripCode, "WATCHING", buildWatchingSnapshot(scripCode, strategy, direction, score, price, true));
            return;
        }

        // --- TRANSITION TO WATCHING ---
        transitionTo(scripCode, "WATCHING", buildWatchingSnapshot(scripCode, strategy, direction, score, price, false));

        // --- GATE VALIDATION ---
        Map<String, Object> gateContext = buildGateContext(scripCode, strategy, direction, score, price, rawSignal);
        ChainResult gateResult = gateChain.evaluate(scripCode, strategy, gateContext);

        if (gateResult.isPassed() && gateResult.getTotalScore() >= gateMinScore) {
            // Gates passed -> ACTIVE signal
            dailySignalCount.merge(dailyKey, 1, Integer::sum);

            log.info("[ORCHESTRATOR] ACTIVE SIGNAL: {} {} {} | gate_score={} | signals_today={}",
                    scripCode, strategy, direction, String.format("%.1f", gateResult.getTotalScore()), todayCount + 1);

            transitionTo(scripCode, "ACTIVE", buildActiveSnapshot(scripCode, strategy, direction,
                    score, price, gateResult));

        } else {
            // Gates blocked -> stay WATCHING, publish as opportunity
            String blockReason = gateResult.isPassed()
                    ? String.format("Gate score %.1f < minimum %.1f", gateResult.getTotalScore(), gateMinScore)
                    : "Required gate failed: " + getBlockingGate(gateResult);

            log.debug("[ORCHESTRATOR] {} gates blocked: {}", scripCode, blockReason);

            publishOpportunity(scripCode, strategy, direction, gateResult.getTotalScore(), price,
                    blockReason, "Waiting for conditions");
        }
    }

    // ==================== CONFLICT DETECTION ====================

    private String checkConflict(String scripCode, String currentStrategy, String currentDirection) {
        SignalDirection fudkii = fudkiiDirections.get(scripCode);
        SignalDirection pivot = pivotDirections.get(scripCode);

        if (fudkii == null || pivot == null) {
            return "OK"; // Only one signal type active, no conflict
        }

        // Check if both are recent (within TTL)
        long now = Instant.now().toEpochMilli();
        long ttlMs = signalTtlMinutes * 60_000L;
        if ((now - fudkii.timestamp.toEpochMilli()) > ttlMs ||
                (now - pivot.timestamp.toEpochMilli()) > ttlMs) {
            return "OK"; // One signal is stale
        }

        // Check direction conflict
        boolean fudkiiBullish = "BULLISH".equals(fudkii.direction);
        boolean pivotBullish = "BULLISH".equals(pivot.direction);

        if (fudkiiBullish != pivotBullish) {
            return "CONFLICTED";
        }

        return "ALIGNED"; // Both agree on direction
    }

    // ==================== STATE TRANSITIONS ====================

    private void transitionTo(String scripCode, String newState, Map<String, Object> snapshot) {
        long now = System.currentTimeMillis();

        InstrumentState prev = instrumentStates.get(scripCode);
        String prevState = prev != null ? prev.state : "IDLE";

        InstrumentState next = new InstrumentState();
        next.state = newState;
        next.stateEntryTime = now;
        next.previousState = prevState;
        if (snapshot != null) {
            next.primaryStrategy = (String) snapshot.getOrDefault("primaryStrategy", "");
        }

        instrumentStates.put(scripCode, next);

        log.info("[STATE_TRANSITION] {} : {} -> {} | strategy={}",
                scripCode, prevState, newState,
                next.primaryStrategy != null ? next.primaryStrategy : "n/a");

        // Build and publish full snapshot
        if (snapshot == null) {
            snapshot = new HashMap<>();
        }
        snapshot.put("scripCode", scripCode);
        snapshot.put("state", newState);
        snapshot.put("stateTimestamp", now);
        snapshot.put("stateEntryTime", now);
        snapshot.put("stateDurationMs", 0);
        if (prev != null) {
            snapshot.put("stateDurationMs", now - prev.stateEntryTime);
        }

        // Daily signal stats
        resetDailyCounterIfNeeded();
        String dailyKey = scripCode + "|" + currentTradeDate;
        snapshot.put("signalsToday", dailySignalCount.getOrDefault(dailyKey, 0));
        snapshot.put("maxSignalsPerDay", maxSignalsPerDay);

        // Cooldown info
        if ("COOLDOWN".equals(newState)) {
            snapshot.put("cooldownRemainingMs", cooldownMinutes * 60_000L);
        } else {
            snapshot.put("cooldownRemainingMs", 0);
        }

        publishStateSnapshot(scripCode, snapshot);
    }

    // ==================== SNAPSHOT BUILDERS ====================

    private Map<String, Object> buildWatchingSnapshot(String scripCode, String strategy,
                                                       String direction, double score,
                                                       double price, boolean conflicted) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("primaryStrategy", strategy);
        snapshot.put("currentPrice", price);
        snapshot.put("superTrendBullish", "BULLISH".equals(direction));
        snapshot.put("superTrendFlip", true);
        snapshot.put("bbPercentB", 0.0);
        snapshot.put("bbSqueezing", false);
        snapshot.put("ofiZscore", 0.0);
        snapshot.put("atr", 0.0);
        snapshot.put("vpin", 0.0);
        snapshot.put("companyName", scripCode);

        // Active setup info
        List<Map<String, Object>> activeSetups = new ArrayList<>();
        Map<String, Object> setup = new HashMap<>();
        setup.put("strategyId", strategy);
        setup.put("setupDescription", strategy + " trigger detected - " + direction);
        setup.put("direction", direction);
        setup.put("keyLevel", price);
        setup.put("watchingStartTime", System.currentTimeMillis());
        setup.put("watchingDurationMs", 0);
        setup.put("progressPercent", conflicted ? 30 : 60);
        setup.put("readyForEntry", false);

        if (conflicted) {
            setup.put("blockingCondition", "FUDKII vs Pivot direction conflict");
            setup.put("notReadyReason", "Signal conflict - wait for alignment");
            setup.put("qualityTier", "CONFLICTED");
        } else {
            setup.put("blockingCondition", "Gate validation pending");
            setup.put("notReadyReason", "Awaiting gate checks");
            setup.put("qualityTier", score >= 70 ? "HIGH" : score >= 50 ? "MEDIUM" : "LOW");
        }

        // Build conditions list
        List<Map<String, Object>> conditions = new ArrayList<>();
        conditions.add(buildCondition(strategy + " Trigger", true, score, 0, ">=", 100, String.format("%.1f", score)));
        conditions.add(buildCondition("Direction", true, 1, 1, "==", 100, direction));
        conditions.add(buildCondition("Gate Validation", false, 0, 1, "==", 0, "Pending"));
        if (conflicted) {
            conditions.add(buildCondition("Signal Alignment", false, 0, 1, "==", 0, "CONFLICTED"));
        }
        setup.put("conditions", conditions);

        activeSetups.add(setup);
        snapshot.put("activeSetups", activeSetups);

        return snapshot;
    }

    private Map<String, Object> buildActiveSnapshot(String scripCode, String strategy,
                                                     String direction, double score,
                                                     double price, ChainResult gateResult) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("primaryStrategy", strategy);
        snapshot.put("currentPrice", price);
        snapshot.put("superTrendBullish", "BULLISH".equals(direction));
        snapshot.put("superTrendFlip", true);
        snapshot.put("companyName", scripCode);
        snapshot.put("ofiZscore", 0.0);
        snapshot.put("atr", 0.0);
        snapshot.put("vpin", 0.0);
        snapshot.put("bbPercentB", 0.0);
        snapshot.put("bbSqueezing", false);

        List<Map<String, Object>> activeSetups = new ArrayList<>();
        Map<String, Object> setup = new HashMap<>();
        setup.put("strategyId", strategy);
        setup.put("setupDescription", strategy + " ACTIVE - " + direction + " signal confirmed");
        setup.put("direction", direction);
        setup.put("keyLevel", price);
        setup.put("watchingStartTime", System.currentTimeMillis());
        setup.put("watchingDurationMs", 0);
        setup.put("progressPercent", 100);
        setup.put("readyForEntry", true);
        setup.put("qualityTier", gateResult.getTotalScore() >= 85 ? "EXCELLENT" :
                gateResult.getTotalScore() >= 70 ? "HIGH" : "MEDIUM");

        // Build conditions from gate results
        List<Map<String, Object>> conditions = new ArrayList<>();
        conditions.add(buildCondition(strategy + " Trigger", true, score, 0, ">=", 100, String.format("%.1f", score)));
        conditions.add(buildCondition("Direction", true, 1, 1, "==", 100, direction));
        conditions.add(buildCondition("Gate Validation", true, gateResult.getTotalScore(), gateMinScore,
                ">=", 100, String.format("%.1f/%.1f", gateResult.getTotalScore(), gateMinScore)));

        // Add individual gate results
        if (gateResult.getGateResults() != null) {
            for (var gr : gateResult.getGateResults()) {
                conditions.add(buildCondition(gr.getGateName(), gr.isPassed(),
                        gr.getScore(), 50, ">=",
                        gr.isPassed() ? 100 : (int) (gr.getScore()),
                        gr.getReason()));
            }
        }
        setup.put("conditions", conditions);
        activeSetups.add(setup);
        snapshot.put("activeSetups", activeSetups);

        return snapshot;
    }

    private Map<String, Object> buildCondition(String name, boolean passed, double currentVal,
                                                double requiredVal, String comparison,
                                                int progressPercent, String displayValue) {
        Map<String, Object> cond = new HashMap<>();
        cond.put("conditionName", name);
        cond.put("passed", passed);
        cond.put("currentValue", currentVal);
        cond.put("requiredValue", requiredVal);
        cond.put("comparison", comparison);
        cond.put("progressPercent", progressPercent);
        cond.put("displayValue", displayValue);
        return cond;
    }

    private Map<String, Object> buildGateContext(String scripCode, String strategy,
                                                  String direction, double score,
                                                  double price, JsonNode rawSignal) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("signalDirection", direction);
        ctx.put("entryPrice", price);
        ctx.put("fudkiiScore", score);
        ctx.put("exchange", rawSignal.path("exchange").asText("N"));
        ctx.put("superTrendBullish", "BULLISH".equals(direction));

        // Extract R:R data if available
        double stopLoss = rawSignal.path("stopLoss").asDouble(0);
        double target = rawSignal.path("target").asDouble(rawSignal.path("target1").asDouble(0));
        if (stopLoss > 0 && target > 0) {
            ctx.put("stopLoss", stopLoss);
            ctx.put("targetPrice", target);
        } else if (price > 0) {
            // Estimate SL/TP from ATR if not provided
            double atrEstimate = price * 0.01; // 1% as rough ATR
            if ("BULLISH".equals(direction)) {
                ctx.put("stopLoss", price - atrEstimate);
                ctx.put("targetPrice", price + atrEstimate * 2);
            } else {
                ctx.put("stopLoss", price + atrEstimate);
                ctx.put("targetPrice", price - atrEstimate * 2);
            }
        }

        // Volume data if available
        ctx.put("volume", rawSignal.path("volume").asDouble(0));
        ctx.put("avgVolume", rawSignal.path("avgVolume").asDouble(1));

        return ctx;
    }

    // ==================== PUBLISHERS ====================

    private void publishStateSnapshot(String scripCode, Map<String, Object> snapshot) {
        try {
            kafkaTemplate.send(STATE_SNAPSHOTS_TOPIC, scripCode, snapshot);
            log.debug("[ORCHESTRATOR] Published state snapshot: {} state={}", scripCode, snapshot.get("state"));
        } catch (Exception e) {
            log.error("[ORCHESTRATOR] Failed to publish state snapshot for {}: {}", scripCode, e.getMessage());
        }
    }

    private void publishOpportunity(String scripCode, String strategy, String direction,
                                     double score, double price, String nextCondition,
                                     String estimatedTimeframe) {
        try {
            Map<String, Object> opportunity = new HashMap<>();
            opportunity.put("scripCode", scripCode);
            opportunity.put("companyName", scripCode);
            opportunity.put("strategyId", strategy);
            opportunity.put("direction", direction);
            opportunity.put("opportunityScore", score);
            opportunity.put("nextConditionNeeded", nextCondition);
            opportunity.put("estimatedTimeframe", estimatedTimeframe);
            opportunity.put("currentPrice", price);
            opportunity.put("keyLevel", price);
            opportunity.put("timestamp", System.currentTimeMillis());

            // Build conditions
            List<Map<String, Object>> conditions = new ArrayList<>();
            conditions.add(buildCondition("Trigger", true, score, 0, ">=", 100, strategy + " fired"));
            conditions.add(buildCondition("Next Step", false, 0, 1, "==", 0, nextCondition));
            opportunity.put("conditions", conditions);

            kafkaTemplate.send(OPPORTUNITIES_TOPIC, scripCode, opportunity);
            log.debug("[ORCHESTRATOR] Published opportunity: {} {} score={}", scripCode, strategy, String.format("%.1f", score));
        } catch (Exception e) {
            log.error("[ORCHESTRATOR] Failed to publish opportunity for {}: {}", scripCode, e.getMessage());
        }
    }

    // ==================== SCHEDULED TASKS ====================

    // Cleanup stale states every minute
    @Scheduled(fixedRate = 60000)
    public void cleanupStaleStates() {
        long now = System.currentTimeMillis();
        long ttlMs = signalTtlMinutes * 60_000L;
        long cooldownMs = cooldownMinutes * 60_000L;

        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, InstrumentState> entry : instrumentStates.entrySet()) {
            InstrumentState state = entry.getValue();
            long age = now - state.stateEntryTime;

            if ("WATCHING".equals(state.state) && age > ttlMs) {
                // WATCHING too long, transition to IDLE
                transitionTo(entry.getKey(), "IDLE", null);
                toRemove.add(entry.getKey());
            } else if ("ACTIVE".equals(state.state) && age > ttlMs) {
                // ACTIVE signal expired without being positioned
                transitionTo(entry.getKey(), "COOLDOWN", null);
            } else if ("COOLDOWN".equals(state.state) && age > cooldownMs) {
                // Cooldown expired
                transitionTo(entry.getKey(), "IDLE", null);
                toRemove.add(entry.getKey());
            }
        }

        // Clean up stale direction caches
        for (var it = fudkiiDirections.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if ((now - entry.getValue().timestamp.toEpochMilli()) > ttlMs) {
                it.remove();
            }
        }
        for (var it = pivotDirections.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if ((now - entry.getValue().timestamp.toEpochMilli()) > ttlMs) {
                it.remove();
            }
        }
        for (var it = microAlphaDirections.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if ((now - entry.getValue().timestamp.toEpochMilli()) > ttlMs) {
                it.remove();
            }
        }

        // Remove IDLE states from memory
        for (String key : toRemove) {
            instrumentStates.remove(key);
        }
    }

    // Publish periodic state summary every 30 seconds
    @Scheduled(fixedRate = 30000)
    public void publishStateSummary() {
        if (instrumentStates.isEmpty()) return;

        int watching = 0, active = 0, cooldown = 0;
        for (InstrumentState state : instrumentStates.values()) {
            switch (state.state) {
                case "WATCHING" -> watching++;
                case "ACTIVE" -> active++;
                case "COOLDOWN" -> cooldown++;
            }
        }

        if (watching > 0 || active > 0 || cooldown > 0) {
            log.info("[ORCHESTRATOR] States: WATCHING={} ACTIVE={} COOLDOWN={} | fudkii_dirs={} pivot_dirs={} microalpha_dirs={}",
                    watching, active, cooldown, fudkiiDirections.size(), pivotDirections.size(), microAlphaDirections.size());
        }
    }

    private void resetDailyCounterIfNeeded() {
        LocalDate today = LocalDate.now(IST);
        if (!today.equals(currentTradeDate)) {
            dailySignalCount.clear();
            currentTradeDate = today;
            log.info("[ORCHESTRATOR] Daily counters reset for {}", today);
        }
    }

    private String getBlockingGate(ChainResult result) {
        if (result.getGateResults() == null) return "Unknown";
        return result.getGateResults().stream()
                .filter(gr -> !gr.isPassed())
                .map(gr -> gr.getGateName() + ": " + gr.getReason())
                .findFirst()
                .orElse("Score too low");
    }

    // ==================== INNER CLASSES ====================

    private static class InstrumentState {
        String state = "IDLE";
        long stateEntryTime;
        String previousState;
        String primaryStrategy;
    }

    private static class SignalDirection {
        final String direction;
        final double score;
        final Instant timestamp;

        SignalDirection(String direction, double score, Instant timestamp) {
            this.direction = direction;
            this.score = score;
            this.timestamp = timestamp;
        }
    }
}
