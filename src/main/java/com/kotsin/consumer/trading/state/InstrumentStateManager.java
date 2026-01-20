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
        log.info("[STATE_MGR] Initialized with {} strategies: {}",
                strategies.size(),
                strategies.stream().map(TradingStrategy::getStrategyId).toList());
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

        // Return signal if one was emitted
        TradingSignal signal = currentCycleSignal.get();
        currentCycleSignal.remove();
        return Optional.ofNullable(signal);
    }

    /**
     * Convenience method for processing without FamilyCandle
     */
    public Optional<TradingSignal> processCandle(EnrichedQuantScore score, String timeframe) {
        if (score == null || score.getScripCode() == null) return Optional.empty();
        return processUpdate(score.getScripCode(), null, score);
    }

    // ======================== STATE PROCESSORS ========================

    /**
     * IDLE state: Look for setups forming
     */
    private void processIdleState(InstrumentContext ctx, EnrichedQuantScore score, double currentPrice) {
        // Check daily limit
        AtomicLong dayCount = dailySignalCounts.computeIfAbsent(ctx.familyId, k -> new AtomicLong(0));
        if (dayCount.get() >= MAX_SIGNALS_PER_INSTRUMENT_PER_DAY) {
            return; // Hit daily limit for this instrument
        }

        // Check each strategy for setup forming
        for (TradingStrategy strategy : strategies) {
            Optional<SetupContext> setupOpt = strategy.detectSetupForming(score);

            if (setupOpt.isPresent()) {
                SetupContext setup = setupOpt.get();
                setup.setFamilyId(ctx.familyId);
                setup.setWatchingStartTime(System.currentTimeMillis());

                // Transition to WATCHING
                ctx.state = InstrumentState.WATCHING;
                ctx.activeSetup = setup;
                ctx.watchingStrategy = strategy;
                ctx.watchingStartTime = System.currentTimeMillis();

                log.info("[STATE_MGR] {} IDLE → WATCHING | strategy={} | setup={} | level={:.2f}",
                        ctx.familyId, strategy.getStrategyId(),
                        setup.getSetupDescription(), setup.getKeyLevel());

                // Only watch ONE setup at a time
                return;
            }
        }
    }

    /**
     * WATCHING state: Look for entry trigger
     */
    private void processWatchingState(InstrumentContext ctx, EnrichedQuantScore score, double currentPrice) {
        TradingStrategy strategy = ctx.watchingStrategy;
        SetupContext setup = ctx.activeSetup;

        if (strategy == null || setup == null) {
            // Invalid state, reset
            transitionToIdle(ctx, "INVALID_WATCHING_STATE");
            return;
        }

        // Check for setup invalidation
        Optional<String> invalidation = strategy.checkSetupInvalidation(score, setup);
        if (invalidation.isPresent()) {
            log.info("[STATE_MGR] {} WATCHING → IDLE | INVALIDATED: {}",
                    ctx.familyId, invalidation.get());
            transitionToIdle(ctx, invalidation.get());
            return;
        }

        // Check for timeout
        long watchingDuration = System.currentTimeMillis() - ctx.watchingStartTime;
        long maxWatching = strategy.getParams().getMaxWatchingDurationMs();
        if (maxWatching <= 0) maxWatching = DEFAULT_MAX_WATCHING_MS;

        if (watchingDuration > maxWatching) {
            log.info("[STATE_MGR] {} WATCHING → IDLE | TIMEOUT ({}min)",
                    ctx.familyId, watchingDuration / 60000);
            transitionToIdle(ctx, "WATCHING_TIMEOUT");
            return;
        }

        // Check for entry trigger
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

            // Transition to READY and emit signal
            ctx.state = InstrumentState.READY;
            ctx.pendingSignal = signal;
            ctx.readyTime = System.currentTimeMillis();

            // Emit the ONE signal
            emitSignal(ctx, signal);

            log.info("[STATE_MGR] {} WATCHING → READY | SIGNAL EMITTED | {} {} | entry={:.2f} sl={:.2f} t1={:.2f}",
                    ctx.familyId, signal.getDirection(), strategy.getStrategyId(),
                    signal.getEntryPrice(), signal.getStopLoss(), signal.getTarget1());
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
        ctx.activeSetup = null;
        ctx.watchingStrategy = null;
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

    // ======================== INNER CLASSES ========================

    @Data
    private static class InstrumentContext {
        final String familyId;
        InstrumentState state = InstrumentState.IDLE;

        // WATCHING state
        TradingStrategy watchingStrategy;
        SetupContext activeSetup;
        long watchingStartTime;

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
    }
}
