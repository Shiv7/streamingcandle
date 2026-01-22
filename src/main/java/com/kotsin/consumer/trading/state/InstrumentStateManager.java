package com.kotsin.consumer.trading.state;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.enrichment.EnrichedQuantScoreCalculator.EnrichedQuantScore;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal;
import com.kotsin.consumer.trading.model.Position;
import com.kotsin.consumer.trading.model.TradeOutcome;
import com.kotsin.consumer.trading.model.TradeOutcome.ExitReason;
import com.kotsin.consumer.trading.strategy.TradingStrategy;
import com.kotsin.consumer.trading.strategy.TradingStrategy.ExitSignal;
import com.kotsin.consumer.trading.strategy.TradingStrategy.SetupContext;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * InstrumentStateManager - THE BRAIN of the trading system.
 *
 * This class:
 * 1. Tracks state for EVERY instrument (IDLE, WATCHING, READY, POSITIONED, COOLDOWN)
 * 2. Evaluates strategies to detect setups and triggers
 * 3. Emits ONE signal per trade lifecycle
 * 4. Manages positions until exit
 * 5. Enforces cooldown periods
 *
 * ALL signal generation goes through here. No exceptions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstrumentStateManager {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final List<TradingStrategy> strategies;

    // Dashboard integration - lazy to break circular dependency
    private StateSnapshotPublisher stateSnapshotPublisher;

    @Autowired
    public void setStateSnapshotPublisher(@Lazy StateSnapshotPublisher publisher) {
        this.stateSnapshotPublisher = publisher;
    }

    // ======================== CONFIGURATION ========================

    private static final String TRADING_SIGNALS_TOPIC = "trading-signals-v2";
    private static final String TRADE_OUTCOMES_TOPIC = "trade-outcomes";

    // Cooldown after trade
    private static final long DEFAULT_COOLDOWN_MS = 30 * 60 * 1000; // 30 minutes

    // Max watching duration (if no trigger, go back to IDLE)
    private static final long DEFAULT_MAX_WATCHING_MS = 60 * 60 * 1000; // 60 minutes

    // Max position duration (time stop)
    private static final long DEFAULT_MAX_POSITION_MS = 4 * 60 * 60 * 1000; // 4 hours

    // Daily limits
    private static final int MAX_SIGNALS_PER_INSTRUMENT_PER_DAY = 3;
    private static final int MAX_TOTAL_POSITIONS = 10;

    // ======================== STATE ========================

    // State per instrument
    private final Map<String, InstrumentContext> instrumentStates = new ConcurrentHashMap<>();

    // Active positions
    private final Map<String, Position> activePositions = new ConcurrentHashMap<>();

    // Daily signal counts
    private final Map<String, AtomicLong> dailySignalCounts = new ConcurrentHashMap<>();
    private volatile long currentDay = getCurrentDay();

    // Trade outcomes for today
    private final List<TradeOutcome> todayOutcomes = Collections.synchronizedList(new ArrayList<>());

    // Statistics
    private final AtomicLong totalSignalsEmitted = new AtomicLong(0);
    private final AtomicLong totalTradesCompleted = new AtomicLong(0);
    private final AtomicLong wins = new AtomicLong(0);
    private final AtomicLong losses = new AtomicLong(0);

    @PostConstruct
    public void init() {
        log.info("[STATE_MGR] ====== INITIALIZATION START ======");
        log.info("[STATE_MGR] Initialized with {} strategies: {}",
                strategies.size(),
                strategies.stream().map(TradingStrategy::getStrategyId).toList());

        // DIAGNOSTIC: Log each strategy details
        for (TradingStrategy strategy : strategies) {
            log.info("[STATE_MGR] Strategy registered: {} | description: {} | params: {}",
                    strategy.getStrategyId(),
                    strategy.getDescription(),
                    strategy.getParams());
        }

        log.info("[STATE_MGR] KafkaTemplate available: {}", kafkaTemplate != null);
        log.info("[STATE_MGR] Signal topic: {}", TRADING_SIGNALS_TOPIC);
        log.info("[STATE_MGR] Max signals/instrument/day: {}", MAX_SIGNALS_PER_INSTRUMENT_PER_DAY);
        log.info("[STATE_MGR] Max total positions: {}", MAX_TOTAL_POSITIONS);
        log.info("[STATE_MGR] ====== INITIALIZATION COMPLETE ======");
    }

    // ======================== MAIN PROCESSING ========================

    // Track signal emitted in current processing cycle
    private final ThreadLocal<TradingSignal> currentCycleSignal = new ThreadLocal<>();

    /**
     * Process a candle update for an instrument.
     * This is the MAIN entry point - called on every candle.
     *
     * @param familyId The instrument family ID
     * @param candle The family candle (can be null - we'll use score.getClose())
     * @param score The enriched quant score (all 68 fields)
     * @return Optional containing the signal if one was emitted during this cycle
     */
    public Optional<TradingSignal> processUpdate(String familyId, FamilyCandle candle, EnrichedQuantScore score) {
        if (familyId == null || score == null) return Optional.empty();

        // Clear signal from previous cycle
        currentCycleSignal.remove();

        // Reset daily counters if needed
        resetDailyCountersIfNeeded();

        // Get or create instrument context
        InstrumentContext ctx = instrumentStates.computeIfAbsent(familyId,
                k -> new InstrumentContext(familyId));

        // Get current price
        double currentPrice = score.getClose() > 0 ? score.getClose() :
                (candle != null && candle.getFuture() != null ? candle.getFuture().getClose() : 0);

        // Process based on current state
        switch (ctx.state) {
            case IDLE -> processIdleState(ctx, score, currentPrice);
            case WATCHING -> processWatchingState(ctx, score, currentPrice);
            case READY -> processReadyState(ctx, score, currentPrice);
            case POSITIONED -> processPositionedState(ctx, score, currentPrice);
            case COOLDOWN -> processCooldownState(ctx, score, currentPrice);
        }

        // Publish state snapshot to dashboard (if publisher is available)
        if (stateSnapshotPublisher != null) {
            try {
                stateSnapshotPublisher.publishSnapshot(familyId, score);
            } catch (Exception e) {
                log.warn("[STATE_MGR] Failed to publish snapshot for {}: {}", familyId, e.getMessage());
            }
        }

        // Return signal if one was emitted
        TradingSignal signal = currentCycleSignal.get();
        currentCycleSignal.remove();
        return Optional.ofNullable(signal);
    }

    /**
     * Convenience method for processing without FamilyCandle
     */
    public Optional<TradingSignal> processCandle(EnrichedQuantScore score, String timeframe) {
        // DIAGNOSTIC: Trace entry into processCandle
        if (score == null) {
            log.warn("[STATE_MGR_DIAG] processCandle called with NULL score | timeframe={}", timeframe);
            return Optional.empty();
        }

        String scripCode = score.getScripCode();
        String familyId = score.getBaseScore() != null ? score.getBaseScore().getFamilyId() : "unknown";

        if (scripCode == null) {
            log.warn("[STATE_MGR_DIAG] scripCode is NULL | familyId={} | timeframe={} | " +
                    "hasBaseScore={} | hasTech={} | close={} | exchange={}",
                    familyId, timeframe,
                    score.getBaseScore() != null,
                    score.getTechnicalContext() != null,
                    score.getClose(),
                    score.getExchange());
            return Optional.empty();
        }

        // FRESHNESS LOGGING: Show incoming data timestamp and key values
        var tech = score.getTechnicalContext();
        double ofi = 0;
        if (score.getHistoricalContext() != null && score.getHistoricalContext().getOfiContext() != null) {
            ofi = score.getHistoricalContext().getOfiContext().getZscore();
        }

        log.debug("[FRESH_DATA] {} | tf={} | ts={} | price={} | ofi={} | atr={} | stBullish={} | stFlip={} | bbPctB={}",
                scripCode, timeframe,
                score.getPriceTimestamp(),
                score.getClose(),
                String.format("%.2f", ofi),
                tech != null ? String.format("%.2f", tech.getAtr()) : "null",
                tech != null ? tech.isSuperTrendBullish() : "null",
                tech != null ? tech.isSuperTrendFlip() : "null",
                tech != null ? String.format("%.3f", tech.getBbPercentB()) : "null");

        return processUpdate(scripCode, null, score);
    }

    // ======================== STATE PROCESSORS ========================

    /**
     * IDLE state: Look for setups forming
     * NOW CHECKS ALL STRATEGIES IN PARALLEL - multiple setups can be tracked simultaneously
     */
    private void processIdleState(InstrumentContext ctx, EnrichedQuantScore score, double currentPrice) {
        // DIAGNOSTIC: Entry into IDLE state processing
        log.debug("[STATE_MGR_DIAG] processIdleState ENTRY | familyId={} | price={} | strategiesCount={}",
                ctx.familyId, currentPrice, strategies.size());

        // Check daily limit
        AtomicLong dayCount = dailySignalCounts.computeIfAbsent(ctx.familyId, k -> new AtomicLong(0));
        if (dayCount.get() >= MAX_SIGNALS_PER_INSTRUMENT_PER_DAY) {
            log.debug("[STATE_MGR_DIAG] {} IDLE - DAILY LIMIT HIT | count={}/{}",
                    ctx.familyId, dayCount.get(), MAX_SIGNALS_PER_INSTRUMENT_PER_DAY);
            return; // Hit daily limit for this instrument
        }

        // DIAGNOSTIC: Check TechnicalContext availability
        var tech = score.getTechnicalContext();
        if (tech != null) {
            log.debug("[STATE_MGR_DIAG] {} IDLE - TechnicalContext | bbSqueeze={} | stFlip={} | stBullish={} | " +
                    "dailyPP={} | dailyS1={} | dailyR1={}",
                    ctx.familyId,
                    tech.isBbSqueezing(),
                    tech.isSuperTrendFlip(),
                    tech.isSuperTrendBullish(),
                    tech.getDailyPivot(),
                    tech.getDailyS1(),
                    tech.getDailyR1());
        } else {
            log.warn("[STATE_MGR_DIAG] {} IDLE - TechnicalContext is NULL!", ctx.familyId);
        }

        // Check ALL strategies for setup forming (PARALLEL evaluation)
        int setupsDetected = 0;
        for (TradingStrategy strategy : strategies) {
            log.trace("[STATE_MGR_DIAG] {} IDLE - Evaluating strategy: {}", ctx.familyId, strategy.getStrategyId());

            Optional<SetupContext> setupOpt = strategy.detectSetupForming(score);

            if (setupOpt.isPresent()) {
                SetupContext setup = setupOpt.get();
                setup.setFamilyId(ctx.familyId);
                setup.setWatchingStartTime(System.currentTimeMillis());

                // Add to watching setups (parallel tracking)
                ctx.addWatchingSetup(strategy, setup);
                setupsDetected++;

                log.info("[STATE_MGR] {} SETUP DETECTED | strategy={} | setup={} | level={:.2f}",
                        ctx.familyId, strategy.getStrategyId(),
                        setup.getSetupDescription(), setup.getKeyLevel());

                // CONTINUE checking other strategies - don't return!
            } else {
                log.trace("[STATE_MGR_DIAG] {} IDLE - No setup from {} | price={}",
                        ctx.familyId, strategy.getStrategyId(), currentPrice);
            }
        }

        // If any setups were detected, transition to WATCHING
        if (setupsDetected > 0) {
            ctx.state = InstrumentState.WATCHING;
            ctx.watchingStartTime = System.currentTimeMillis();
            log.info("[STATE_MGR] {} IDLE → WATCHING | {} parallel setups tracked: {}",
                    ctx.familyId, setupsDetected,
                    ctx.watchingSetups.keySet().stream().map(TradingStrategy::getStrategyId).toList());
        } else {
            log.trace("[STATE_MGR_DIAG] {} IDLE - No setup detected from any strategy", ctx.familyId);
        }
    }

    /**
     * WATCHING state: Look for entry trigger
     * NOW CHECKS ALL PARALLEL SETUPS - first one that triggers fires the signal
     */
    private void processWatchingState(InstrumentContext ctx, EnrichedQuantScore score, double currentPrice) {
        // Check if we have any watching setups
        if (ctx.watchingSetups.isEmpty()) {
            log.warn("[STATE_MGR_DIAG] {} WATCHING - No setups tracked, returning to IDLE", ctx.familyId);
            transitionToIdle(ctx, "NO_SETUPS_TRACKED");
            return;
        }

        log.debug("[STATE_MGR_DIAG] {} WATCHING - Checking {} parallel setups: {}",
                ctx.familyId, ctx.watchingSetups.size(),
                ctx.watchingSetups.keySet().stream().map(TradingStrategy::getStrategyId).toList());

        // Track setups to remove (invalidated or timed out)
        List<TradingStrategy> toRemove = new ArrayList<>();

        // Check for timeout (global for all setups)
        long watchingDuration = System.currentTimeMillis() - ctx.watchingStartTime;

        // Check ALL setups for entry triggers and invalidation
        for (Map.Entry<TradingStrategy, SetupContext> entry : ctx.watchingSetups.entrySet()) {
            TradingStrategy strategy = entry.getKey();
            SetupContext setup = entry.getValue();

            // Check for setup invalidation
            Optional<String> invalidation = strategy.checkSetupInvalidation(score, setup);
            if (invalidation.isPresent()) {
                log.info("[STATE_MGR] {} SETUP INVALIDATED | strategy={} | reason={}",
                        ctx.familyId, strategy.getStrategyId(), invalidation.get());
                toRemove.add(strategy);
                continue; // Check next setup
            }

            // Check for timeout per strategy
            long maxWatching = strategy.getParams().getMaxWatchingDurationMs();
            if (maxWatching <= 0) maxWatching = DEFAULT_MAX_WATCHING_MS;

            if (watchingDuration > maxWatching) {
                log.info("[STATE_MGR] {} SETUP TIMEOUT | strategy={} | duration={}min",
                        ctx.familyId, strategy.getStrategyId(), watchingDuration / 60000);
                toRemove.add(strategy);
                continue; // Check next setup
            }

            // Check for entry trigger
            log.debug("[STATE_MGR_DIAG] {} Checking entry trigger for {} | setup={}",
                    ctx.familyId, strategy.getStrategyId(), setup.getSetupDescription());

            Optional<TradingSignal> signalOpt = strategy.checkEntryTrigger(score, setup);

            if (signalOpt.isPresent()) {
                TradingSignal signal = signalOpt.get();

                // Check position limit
                if (activePositions.size() >= MAX_TOTAL_POSITIONS) {
                    log.warn("[STATE_MGR] {} WATCHING → IDLE | MAX_POSITIONS_REACHED ({})",
                            ctx.familyId, activePositions.size());
                    transitionToIdle(ctx, "MAX_POSITIONS_REACHED");
                    return;
                }

                // ENTRY TRIGGERED! Store the winning strategy for reference
                ctx.watchingStrategy = strategy;
                ctx.activeSetup = setup;

                // Transition to READY and emit signal
                ctx.state = InstrumentState.READY;
                ctx.pendingSignal = signal;
                ctx.readyTime = System.currentTimeMillis();

                // Emit the ONE signal
                emitSignal(ctx, signal);

                log.info("[STATE_MGR] {} WATCHING → READY | SIGNAL EMITTED | {} {} | entry={:.2f} sl={:.2f} t1={:.2f}",
                        ctx.familyId, signal.getDirection(), strategy.getStrategyId(),
                        signal.getEntryPrice(), signal.getStopLoss(), signal.getTarget1());

                // Clear other setups - we're committed to this one
                ctx.watchingSetups.clear();
                return; // Signal emitted, done processing
            } else {
                log.trace("[STATE_MGR_DIAG] {} No entry trigger from {} yet",
                        ctx.familyId, strategy.getStrategyId());
            }
        }

        // Remove invalidated/timed-out setups
        for (TradingStrategy s : toRemove) {
            ctx.watchingSetups.remove(s);
        }

        // If all setups were invalidated/timed-out, return to IDLE
        if (ctx.watchingSetups.isEmpty()) {
            log.info("[STATE_MGR] {} WATCHING → IDLE | All setups invalidated or timed out",
                    ctx.familyId);
            transitionToIdle(ctx, "ALL_SETUPS_INVALIDATED");
        }
    }

    /**
     * READY state: Waiting for fill, then move to POSITIONED
     * For now, auto-fill immediately (paper trading)
     */
    private void processReadyState(InstrumentContext ctx, EnrichedQuantScore score, double currentPrice) {
        if (ctx.pendingSignal == null) {
            transitionToIdle(ctx, "NO_PENDING_SIGNAL");
            return;
        }

        // Auto-fill for paper trading (in live, this would wait for execution confirmation)
        Position position = Position.fromSignal(ctx.pendingSignal, 1); // 1 share as requested
        activePositions.put(ctx.familyId, position);

        ctx.state = InstrumentState.POSITIONED;
        ctx.position = position;
        ctx.pendingSignal = null;

        log.info("[STATE_MGR] {} READY → POSITIONED | FILLED | {} {} @ {:.2f}",
                ctx.familyId, position.getDirection(), ctx.watchingStrategy.getStrategyId(),
                position.getEntryPrice());
    }

    /**
     * POSITIONED state: Manage the trade, watch for exit
     */
    private void processPositionedState(InstrumentContext ctx, EnrichedQuantScore score, double currentPrice) {
        Position position = ctx.position;
        TradingStrategy strategy = ctx.watchingStrategy;

        if (position == null || strategy == null) {
            transitionToCooldown(ctx, null, "INVALID_POSITIONED_STATE");
            return;
        }

        // Update position with current price
        position.updatePrice(currentPrice);

        // Check time stop
        long positionDuration = System.currentTimeMillis() - position.getEntryTime().toEpochMilli();
        long maxPosition = strategy.getParams().getMaxPositionDurationMs();
        if (maxPosition <= 0) maxPosition = DEFAULT_MAX_POSITION_MS;

        if (positionDuration > maxPosition) {
            log.info("[STATE_MGR] {} TIME_STOP after {}min | P&L={:.2f} ({:.2f}%)",
                    ctx.familyId, positionDuration / 60000,
                    position.getUnrealizedPnL(), position.getPnLPercent());
            exitPosition(ctx, currentPrice, ExitReason.TIME_STOP);
            return;
        }

        // Check stop loss
        if (position.isStopLossHit()) {
            log.info("[STATE_MGR] {} STOP_LOSS_HIT @ {:.2f} | P&L={:.2f} ({:.2f}%)",
                    ctx.familyId, currentPrice,
                    position.getUnrealizedPnL(), position.getPnLPercent());
            exitPosition(ctx, currentPrice, ExitReason.STOP_LOSS_HIT);
            return;
        }

        // Check target 1
        if (!position.isTarget1Hit() && position.isTarget1Hit()) {
            position.setTarget1Hit(true);
            // Trail stop to breakeven
            position.trailStopLoss(position.getEntryPrice());
            log.info("[STATE_MGR] {} TARGET_1_HIT @ {:.2f} | Trailing stop to breakeven",
                    ctx.familyId, currentPrice);
        }

        // Check target 2 (full exit)
        if (position.isTarget2Hit()) {
            log.info("[STATE_MGR] {} TARGET_2_HIT @ {:.2f} | P&L={:.2f} ({:.2f}%)",
                    ctx.familyId, currentPrice,
                    position.getUnrealizedPnL(), position.getPnLPercent());
            exitPosition(ctx, currentPrice, ExitReason.TARGET_2_HIT);
            return;
        }

        // Check strategy-specific exit conditions
        Optional<ExitSignal> exitOpt = strategy.checkExitConditions(position, score);
        if (exitOpt.isPresent()) {
            ExitSignal exit = exitOpt.get();
            log.info("[STATE_MGR] {} STRATEGY_EXIT: {} | P&L={:.2f} ({:.2f}%)",
                    ctx.familyId, exit.getDescription(),
                    position.getUnrealizedPnL(), position.getPnLPercent());
            exitPosition(ctx, exit.getExitPrice() > 0 ? exit.getExitPrice() : currentPrice, exit.getReason());
        }
    }

    /**
     * COOLDOWN state: Wait for cooldown to expire
     */
    private void processCooldownState(InstrumentContext ctx, EnrichedQuantScore score, double currentPrice) {
        long cooldownDuration = System.currentTimeMillis() - ctx.cooldownStartTime;
        long cooldownRequired = DEFAULT_COOLDOWN_MS;

        if (ctx.watchingStrategy != null && ctx.watchingStrategy.getParams().getCooldownDurationMs() > 0) {
            cooldownRequired = ctx.watchingStrategy.getParams().getCooldownDurationMs();
        }

        if (cooldownDuration >= cooldownRequired) {
            log.info("[STATE_MGR] {} COOLDOWN → IDLE | Cooldown complete ({}min)",
                    ctx.familyId, cooldownDuration / 60000);
            transitionToIdle(ctx, "COOLDOWN_COMPLETE");
        }
    }

    // ======================== STATE TRANSITIONS ========================

    private void transitionToIdle(InstrumentContext ctx, String reason) {
        ctx.state = InstrumentState.IDLE;
        ctx.clearWatchingSetups();  // Clear all parallel setups
        ctx.pendingSignal = null;
        ctx.position = null;
        ctx.watchingStartTime = 0;
        ctx.readyTime = 0;
        ctx.cooldownStartTime = 0;

        log.debug("[STATE_MGR] {} → IDLE | reason={}", ctx.familyId, reason);
    }

    private void transitionToCooldown(InstrumentContext ctx, TradeOutcome outcome, String reason) {
        ctx.state = InstrumentState.COOLDOWN;
        ctx.cooldownStartTime = System.currentTimeMillis();
        ctx.position = null;
        ctx.pendingSignal = null;
        activePositions.remove(ctx.familyId);

        if (outcome != null) {
            todayOutcomes.add(outcome);
            totalTradesCompleted.incrementAndGet();
            if (outcome.isWin()) {
                wins.incrementAndGet();
            } else {
                losses.incrementAndGet();
            }

            // Publish outcome
            try {
                kafkaTemplate.send(TRADE_OUTCOMES_TOPIC, ctx.familyId, outcome);
            } catch (Exception e) {
                log.error("[STATE_MGR] Failed to publish trade outcome: {}", e.getMessage());
            }
        }

        log.info("[STATE_MGR] {} → COOLDOWN | reason={}", ctx.familyId, reason);
    }

    private void exitPosition(InstrumentContext ctx, double exitPrice, ExitReason reason) {
        if (ctx.position == null) {
            transitionToCooldown(ctx, null, "NO_POSITION_TO_EXIT");
            return;
        }

        TradeOutcome outcome = TradeOutcome.fromPosition(ctx.position, exitPrice, reason);
        log.info("[STATE_MGR] TRADE_CLOSED | {}", outcome.getSummary());

        transitionToCooldown(ctx, outcome, reason.name());
    }

    private void emitSignal(InstrumentContext ctx, TradingSignal signal) {
        try {
            // Store in ThreadLocal for return to caller
            currentCycleSignal.set(signal);

            // Publish to Kafka
            kafkaTemplate.send(TRADING_SIGNALS_TOPIC, ctx.familyId, signal);
            totalSignalsEmitted.incrementAndGet();
            dailySignalCounts.computeIfAbsent(ctx.familyId, k -> new AtomicLong(0)).incrementAndGet();

            log.info("[STATE_MGR] SIGNAL_EMITTED | {} {} {} | total_today={} | instrument_today={}",
                    ctx.familyId, signal.getDirection(), signal.getSetupId(),
                    totalSignalsEmitted.get(),
                    dailySignalCounts.get(ctx.familyId).get());
        } catch (Exception e) {
            log.error("[STATE_MGR] Failed to emit signal for {}: {}", ctx.familyId, e.getMessage());
        }
    }

    // ======================== UTILITIES ========================

    private void resetDailyCountersIfNeeded() {
        long today = getCurrentDay();
        if (today != currentDay) {
            currentDay = today;
            dailySignalCounts.clear();
            todayOutcomes.clear();
            log.info("[STATE_MGR] Daily reset complete");
        }
    }

    private long getCurrentDay() {
        return System.currentTimeMillis() / (24 * 60 * 60 * 1000);
    }

    // ======================== STATISTICS ========================

    public String getStats() {
        long totalWins = wins.get();
        long totalLosses = losses.get();
        long total = totalWins + totalLosses;
        double winRate = total > 0 ? (totalWins * 100.0 / total) : 0;

        return String.format(
                "[STATE_MGR] signals=%d | trades=%d (W:%d L:%d, %.1f%%) | watching=%d | positioned=%d | cooldown=%d",
                totalSignalsEmitted.get(),
                total, totalWins, totalLosses, winRate,
                countByState(InstrumentState.WATCHING),
                countByState(InstrumentState.POSITIONED),
                countByState(InstrumentState.COOLDOWN)
        );
    }

    private long countByState(InstrumentState state) {
        return instrumentStates.values().stream()
                .filter(ctx -> ctx.state == state)
                .count();
    }

    public InstrumentState getState(String familyId) {
        InstrumentContext ctx = instrumentStates.get(familyId);
        return ctx != null ? ctx.state : InstrumentState.IDLE;
    }

    public Position getPosition(String familyId) {
        return activePositions.get(familyId);
    }

    public List<TradeOutcome> getTodayOutcomes() {
        return new ArrayList<>(todayOutcomes);
    }

    // ======================== DASHBOARD INTEGRATION ========================

    /**
     * Get signals count today for a specific instrument.
     */
    public int getSignalsTodayForInstrument(String familyId) {
        AtomicLong count = dailySignalCounts.get(familyId);
        return count != null ? (int) count.get() : 0;
    }

    /**
     * Get max signals allowed per day per instrument.
     */
    public int getMaxSignalsPerDay() {
        return MAX_SIGNALS_PER_INSTRUMENT_PER_DAY;
    }

    /**
     * Get watching setups for an instrument.
     * Returns map of strategyId -> SetupContext
     */
    public Map<String, TradingStrategy.SetupContext> getWatchingSetups(String familyId) {
        InstrumentContext ctx = instrumentStates.get(familyId);
        if (ctx == null || ctx.watchingSetups.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, TradingStrategy.SetupContext> result = new HashMap<>();
        for (Map.Entry<TradingStrategy, SetupContext> entry : ctx.watchingSetups.entrySet()) {
            result.put(entry.getKey().getStrategyId(), entry.getValue());
        }
        return result;
    }

    /**
     * Get near-opportunities (instruments close to triggering signals).
     * Returns instruments in WATCHING state with high progress.
     */
    public List<com.kotsin.consumer.trading.state.dto.StrategyOpportunity> getNearOpportunities() {
        List<com.kotsin.consumer.trading.state.dto.StrategyOpportunity> opportunities = new ArrayList<>();

        for (Map.Entry<String, InstrumentContext> entry : instrumentStates.entrySet()) {
            InstrumentContext ctx = entry.getValue();
            if (ctx.state != InstrumentState.WATCHING || ctx.watchingSetups.isEmpty()) {
                continue;
            }

            String scripCode = entry.getKey();

            for (Map.Entry<TradingStrategy, SetupContext> setupEntry : ctx.watchingSetups.entrySet()) {
                TradingStrategy strategy = setupEntry.getKey();
                SetupContext setup = setupEntry.getValue();

                // Calculate opportunity score (simplified - based on setup age and alignment)
                long watchingDuration = System.currentTimeMillis() - setup.getWatchingStartTime();
                double durationFactor = Math.min(1.0, watchingDuration / (30.0 * 60 * 1000)); // Max at 30 min

                // Base score on setup having valid levels
                double baseScore = 50;
                if (setup.getKeyLevel() > 0 && setup.getProposedStop() > 0 && setup.getProposedTarget1() > 0) {
                    baseScore = 70;
                }
                if (setup.isSuperTrendAligned()) {
                    baseScore += 10;
                }

                double opportunityScore = baseScore + (durationFactor * 10);

                // Only include if score >= 60
                if (opportunityScore >= 60) {
                    com.kotsin.consumer.trading.state.dto.StrategyOpportunity opp =
                            com.kotsin.consumer.trading.state.dto.StrategyOpportunity.builder()
                                    .scripCode(scripCode)
                                    .companyName(scripCode) // Will be enriched by dashboard
                                    .strategyId(strategy.getStrategyId())
                                    .direction(setup.getDirection() != null ? setup.getDirection().name() : "LONG")
                                    .opportunityScore(opportunityScore)
                                    .currentPrice(setup.getPriceAtDetection())
                                    .keyLevel(setup.getKeyLevel())
                                    .nextConditionNeeded("Entry trigger")
                                    .estimatedTimeframe("Monitoring...")
                                    .build();

                    opportunities.add(opp);
                }
            }
        }

        // Sort by score descending, limit to top 20
        opportunities.sort((a, b) -> Double.compare(b.getOpportunityScore(), a.getOpportunityScore()));
        if (opportunities.size() > 20) {
            opportunities = opportunities.subList(0, 20);
        }

        return opportunities;
    }

    /**
     * Get all instrument states summary by state.
     */
    public Map<InstrumentState, List<String>> getStatesSummary() {
        return instrumentStates.entrySet().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        e -> e.getValue().state,
                        java.util.stream.Collectors.mapping(Map.Entry::getKey, java.util.stream.Collectors.toList())
                ));
    }

    // ======================== INNER CLASSES ========================

    @Data
    private static class InstrumentContext {
        final String familyId;
        InstrumentState state = InstrumentState.IDLE;

        // WATCHING state - NOW SUPPORTS MULTIPLE PARALLEL STRATEGIES
        // Key: Strategy, Value: SetupContext for that strategy
        Map<TradingStrategy, SetupContext> watchingSetups = new HashMap<>();
        long watchingStartTime;

        // Legacy single-strategy fields (used when a signal fires)
        TradingStrategy watchingStrategy;
        SetupContext activeSetup;

        // READY state
        TradingSignal pendingSignal;
        long readyTime;

        // POSITIONED state
        Position position;

        // COOLDOWN state
        long cooldownStartTime;

        InstrumentContext(String familyId) {
            this.familyId = familyId;
        }

        void addWatchingSetup(TradingStrategy strategy, SetupContext setup) {
            watchingSetups.put(strategy, setup);
        }

        void clearWatchingSetups() {
            watchingSetups.clear();
            watchingStrategy = null;
            activeSetup = null;
        }

        boolean hasAnyWatchingSetup() {
            return !watchingSetups.isEmpty();
        }
    }
}
