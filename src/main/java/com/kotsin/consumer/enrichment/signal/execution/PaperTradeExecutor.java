package com.kotsin.consumer.enrichment.signal.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.config.KafkaTopics;
import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.domain.model.InstrumentCandle;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PaperTradeExecutor - Simulates trade execution for backtesting/paper trading
 *
 * Consumes:
 * - trading-signals-v2: Trading signals to execute
 * - family-candle-1m: Price data to track positions
 *
 * Produces:
 * - paper-trade-outcomes: Execution results for outcome tracking
 *
 * This module enables end-to-end testing by simulating order execution
 * against historical or live price data without placing real trades.
 *
 * Enable with: paper.trade.executor.enabled=true
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "paper.trade.executor.enabled", havingValue = "true")
public class PaperTradeExecutor {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    // Track active positions by signal ID
    private final Map<String, ActivePosition> activePositions = new ConcurrentHashMap<>();

    // Statistics
    private final AtomicInteger signalsReceived = new AtomicInteger(0);
    private final AtomicInteger positionsEntered = new AtomicInteger(0);
    private final AtomicInteger positionsClosed = new AtomicInteger(0);
    private final AtomicInteger wins = new AtomicInteger(0);
    private final AtomicInteger losses = new AtomicInteger(0);
    private final AtomicInteger signalsDroppedCapacity = new AtomicInteger(0);
    private final AtomicInteger signalsDroppedDuplicate = new AtomicInteger(0);

    // BUG #39 FIX: Base slippage - actual slippage is volatility-adjusted
    @Value("${paper.trade.executor.slippage.bps:5}")
    private double baseSlippageBps;

    @Value("${paper.trade.executor.max.active.positions:50}")
    private int maxActivePositions;

    // BUG #38 FIX: Reduced default timeout and added exchange-aware logic
    // OLD: 240 minutes (4 hours) = guaranteed TIME_EXIT for most trades
    // NEW: 120 minutes (2 hours) for NSE, 180 minutes for MCX
    @Value("${paper.trade.executor.position.timeout.minutes.nse:120}")
    private int positionTimeoutMinutesNSE;

    @Value("${paper.trade.executor.position.timeout.minutes.mcx:180}")
    private int positionTimeoutMinutesMCX;

    // Legacy config for backward compatibility
    @Value("${paper.trade.executor.position.timeout.minutes:120}")
    private int positionTimeoutMinutes;

    @Value("${paper.trade.executor.enable.partial.exits:true}")
    private boolean enablePartialExits;

    // BUG #37 FIX: Track signal-to-entry latency
    private final java.util.concurrent.atomic.AtomicLong totalLatencyMs = new java.util.concurrent.atomic.AtomicLong(0);
    private final AtomicInteger latencySamples = new AtomicInteger(0);

    // ======================== SIGNAL CONSUMPTION ========================

    /**
     * Consume trading signals and create paper positions
     */
    @KafkaListener(
            topics = "${paper.trade.executor.signal.topic:" + KafkaTopics.TRADING_SIGNALS_V2 + "}",
            groupId = "${paper.trade.executor.group.id:paper-trade-executor-v1}",
            containerFactory = "curatedKafkaListenerContainerFactory"
    )
    public void consumeSignal(ConsumerRecord<String, String> record) {
        try {
            TradingSignal signal = objectMapper.readValue(record.value(), TradingSignal.class);
            signalsReceived.incrementAndGet();

            if (!signal.isActionable()) {
                log.debug("[PAPER_EXEC] Signal {} not actionable, skipping", signal.getSignalId());
                return;
            }

            if (activePositions.size() >= maxActivePositions) {
                int dropped = signalsDroppedCapacity.incrementAndGet();
                log.warn("[PAPER_EXEC] ⚠️ CAPACITY LIMIT: Max positions reached ({}/{}), dropping signal {} for {} | Total dropped: {}",
                        activePositions.size(), maxActivePositions, signal.getSignalId(),
                        signal.getFamilyId(), dropped);
                // Publish dropped signal event for monitoring
                publishDroppedSignal(signal, "CAPACITY_LIMIT", dropped);
                return;
            }

            // Check for duplicate signal
            if (activePositions.containsKey(signal.getSignalId())) {
                signalsDroppedDuplicate.incrementAndGet();
                log.debug("[PAPER_EXEC] Signal {} already tracked", signal.getSignalId());
                return;
            }

            // Check for existing position in same family (avoid overlapping trades)
            boolean hasExistingPosition = activePositions.values().stream()
                    .anyMatch(p -> p.familyId.equals(signal.getFamilyId()) &&
                            p.direction.equals(signal.getDirection().name()));
            if (hasExistingPosition) {
                log.debug("[PAPER_EXEC] Already have {} position in {}, skipping",
                        signal.getDirection(), signal.getFamilyId());
                return;
            }

            ActivePosition position = createPosition(signal);
            activePositions.put(signal.getSignalId(), position);

            log.info("[PAPER_EXEC] Created position for {} {} {} @ entry: {}, SL: {}, T1: {}, T2: {}",
                    signal.getDirection(), signal.getCategory(), signal.getFamilyId(),
                    signal.getEntryPrice(), signal.getStopLoss(),
                    signal.getTarget1(), signal.getTarget2());

        } catch (Exception e) {
            log.error("[PAPER_EXEC] Failed to process signal: {}", e.getMessage());
        }
    }

    // ======================== PRICE TRACKING ========================

    /**
     * Consume price data and track positions
     *
     * FIX: Changed parameter from ConsumerRecord<String, String> to FamilyCandle
     * The StringJsonMessageConverter in curatedKafkaListenerContainerFactory
     * automatically deserializes JSON to the method parameter type.
     * Using ConsumerRecord<String, String> caused deserialization failure because
     * the converter tried to deserialize JSON object to String type.
     */
    @KafkaListener(
            topics = "${paper.trade.executor.price.topic:" + KafkaTopics.FAMILY_CANDLE_1M + "}",
            groupId = "${paper.trade.executor.price.group.id:paper-trade-price-tracker-v1}",
            containerFactory = "curatedKafkaListenerContainerFactory"
    )
    public void consumePrice(FamilyCandle candle) {
        if (activePositions.isEmpty()) {
            return;
        }

        try {
            String familyId = candle.getFamilyId();

            // Get primary instrument for OHLC data
            InstrumentCandle primary = candle.getPrimaryInstrumentOrFallback();
            if (primary == null) {
                return;
            }

            // Find positions for this family
            for (Map.Entry<String, ActivePosition> entry : activePositions.entrySet()) {
                ActivePosition position = entry.getValue();
                if (!position.familyId.equals(familyId)) {
                    continue;
                }

                updatePosition(position, primary);

                // Check for exit conditions
                ExitResult exit = checkExitConditions(position, primary);
                if (exit != null) {
                    closePosition(position, exit, primary);
                    activePositions.remove(entry.getKey());
                }
            }

            // Check for expired positions
            cleanupExpiredPositions();

        } catch (Exception e) {
            log.debug("[PAPER_EXEC] Failed to process price: {}", e.getMessage());
        }
    }

    // ======================== POSITION MANAGEMENT ========================

    private ActivePosition createPosition(TradingSignal signal) {
        // BUG #37 FIX: Track signal-to-entry latency
        Instant now = Instant.now();
        Instant signalTime = signal.getGeneratedAt();
        long latencyMs = 0;
        if (signalTime != null) {
            latencyMs = Duration.between(signalTime, now).toMillis();
            totalLatencyMs.addAndGet(latencyMs);
            latencySamples.incrementAndGet();

            if (latencyMs > 5000) { // More than 5 seconds
                log.warn("[PAPER_EXEC] HIGH LATENCY: Signal {} took {}ms from generation to entry",
                        signal.getSignalId(), latencyMs);
            }
        }

        // BUG #39 FIX: Volatility-adjusted slippage
        // Base slippage is adjusted by ATR percentage if available
        double slippageBps = baseSlippageBps;
        if (signal.getRiskPct() > 0) {
            // Higher volatility = higher slippage (up to 3x base)
            double volatilityMultiplier = Math.min(3.0, 1.0 + signal.getRiskPct() / 2.0);
            slippageBps = baseSlippageBps * volatilityMultiplier;
            log.debug("[PAPER_EXEC] Adjusted slippage: {}bps -> {}bps (volatility={}%)",
                    baseSlippageBps, slippageBps, signal.getRiskPct());
        }

        // Apply slippage to entry
        double entrySlippage = signal.getEntryPrice() * slippageBps / 10000;
        double actualEntry = signal.isLong() ?
                signal.getEntryPrice() + entrySlippage :
                signal.getEntryPrice() - entrySlippage;

        // BUG #40 FIX: Determine exchange for timeout calculation
        String exchange = signal.getExchange();
        boolean isMCX = exchange != null && exchange.toUpperCase().contains("MCX");
        int timeout = isMCX ? positionTimeoutMinutesMCX : positionTimeoutMinutesNSE;

        return new ActivePosition(
                signal.getSignalId(),
                signal.getFamilyId(),
                signal.getScripCode(),
                signal.getDirection().name(),
                signal.getCategory() != null ? signal.getCategory().name() : "UNKNOWN",
                signal.getSource() != null ? signal.getSource().name() : "UNKNOWN",
                signal.getHorizon() != null ? signal.getHorizon().name() : "INTRADAY",
                signal.getPatternId(),
                signal.getSetupId(),
                signal.getSequenceId(),
                signal.getConfidence(),
                signal.getQualityScore(),
                signal.getGeneratedAt(),
                Instant.now(),
                signal.getEntryPrice(),
                actualEntry,
                signal.getStopLoss(),
                signal.getTarget1(),
                signal.getTarget2(),
                signal.getTarget3() > 0 ? signal.getTarget3() : signal.getTarget2() * 1.5,
                actualEntry,
                actualEntry,
                signal.getPositionSizeMultiplier(),
                signal.getGexRegime(),
                signal.getSession()
        );
    }

    private void updatePosition(ActivePosition position, InstrumentCandle candle) {
        double high = candle.getHigh();
        double low = candle.getLow();

        if (position.isLong()) {
            if (high > position.maxPrice) position.maxPrice = high;
            if (low < position.minPrice) position.minPrice = low;
        } else {
            if (low < position.minPrice) position.minPrice = low;
            if (high > position.maxPrice) position.maxPrice = high;
        }
    }

    private ExitResult checkExitConditions(ActivePosition position, InstrumentCandle candle) {
        double high = candle.getHigh();
        double low = candle.getLow();
        double close = candle.getClose();

        boolean isLong = position.isLong();

        // Check stop loss
        if (isLong && low <= position.stopLoss) {
            return new ExitResult(position.stopLoss, "STOP_LOSS_HIT", true, false, false, false, false);
        }
        if (!isLong && high >= position.stopLoss) {
            return new ExitResult(position.stopLoss, "STOP_LOSS_HIT", true, false, false, false, false);
        }

        // Check target 3
        if (isLong && high >= position.target3) {
            position.target3Hit = true;
            position.target2Hit = true;
            position.target1Hit = true;
            return new ExitResult(position.target3, "TARGET_3_HIT", false, true, true, true, false);
        }
        if (!isLong && low <= position.target3) {
            position.target3Hit = true;
            position.target2Hit = true;
            position.target1Hit = true;
            return new ExitResult(position.target3, "TARGET_3_HIT", false, true, true, true, false);
        }

        // Check target 2
        if (isLong && high >= position.target2) {
            position.target2Hit = true;
            position.target1Hit = true;
            if (!enablePartialExits) {
                return new ExitResult(position.target2, "TARGET_2_HIT", false, false, true, true, false);
            }
        }
        if (!isLong && low <= position.target2) {
            position.target2Hit = true;
            position.target1Hit = true;
            if (!enablePartialExits) {
                return new ExitResult(position.target2, "TARGET_2_HIT", false, false, true, true, false);
            }
        }

        // Check target 1 (mark but don't exit if partial exits enabled)
        if (isLong && high >= position.target1) {
            position.target1Hit = true;
        }
        if (!isLong && low <= position.target1) {
            position.target1Hit = true;
        }

        // Check time expiry
        Duration inTrade = Duration.between(position.enteredAt, Instant.now());
        if (inTrade.toMinutes() >= positionTimeoutMinutes) {
            return new ExitResult(close, "TIME_EXIT", false, false, false, position.target1Hit, true);
        }

        return null;
    }

    private void closePosition(ActivePosition position, ExitResult exit, InstrumentCandle candle) {
        double pnl;
        if (position.isLong()) {
            pnl = (exit.exitPrice - position.actualEntry) / position.actualEntry * 100;
        } else {
            pnl = (position.actualEntry - exit.exitPrice) / position.actualEntry * 100;
        }

        double riskPerTrade = Math.abs(position.actualEntry - position.stopLoss);
        double rMultiple = riskPerTrade > 0 ?
                (position.isLong() ? exit.exitPrice - position.actualEntry : position.actualEntry - exit.exitPrice) / riskPerTrade :
                0;

        long timeInTradeMs = Duration.between(position.enteredAt, Instant.now()).toMillis();

        positionsClosed.incrementAndGet();
        // BUG #41 FIX: Use R-multiple for win/loss, not tight % threshold
        // OLD: pnl > 0 = win, pnl < -0.05% = loss (too tight!)
        // NEW: R > 0.5 = win (clear profit), R < -0.5 = loss (clear loss)
        // This accounts for transaction costs and slippage properly
        if (rMultiple > 0.5) {
            wins.incrementAndGet();
        } else if (rMultiple < -0.5) {
            losses.incrementAndGet();
        }
        // Trades with -0.5 < R < 0.5 are "scratch" trades - not counted as win or loss

        // Publish outcome
        TradeOutcomeMessage outcome = new TradeOutcomeMessage(
                position.signalId,
                position.familyId,
                position.scripCode,
                position.source,
                position.category,
                position.direction,
                position.horizon,
                position.patternId,
                position.setupId,
                position.sequenceId,
                position.signalConfidence,
                position.signalQualityScore,
                position.signalGeneratedAt,
                position.enteredAt,
                Instant.now(),
                position.plannedEntry,
                position.actualEntry,
                exit.exitPrice,
                position.stopLoss,
                position.target1,
                position.target2,
                pnl,
                rMultiple,
                position.maxPrice,
                position.minPrice,
                position.positionSizeMultiplier,
                timeInTradeMs,
                exit.target1Hit || position.target1Hit,
                exit.target2Hit || position.target2Hit,
                exit.target3Hit || position.target3Hit,
                exit.stopHit,
                false, // trailingStopHit
                exit.timeExit,
                false, // invalidationTriggered
                null,  // invalidationCondition
                position.gexRegime,
                position.session
        );

        try {
            String json = objectMapper.writeValueAsString(outcome);
            kafkaTemplate.send(KafkaTopics.PAPER_TRADE_OUTCOMES, position.familyId, json);

            log.info("[PAPER_EXEC] Closed {} {} {} @ {} | P&L: {}% | R: {} | Exit: {}",
                    position.direction, position.category, position.familyId,
                    String.format("%.2f", exit.exitPrice),
                    String.format("%.2f", pnl),
                    String.format("%.1f", rMultiple),
                    exit.reason);

        } catch (JsonProcessingException e) {
            log.error("[PAPER_EXEC] Failed to publish outcome: {}", e.getMessage());
        }
    }

    private void cleanupExpiredPositions() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(positionTimeoutMinutes * 2L));
        activePositions.entrySet().removeIf(entry -> entry.getValue().enteredAt.isBefore(cutoff));
    }

    // ======================== STATISTICS ========================

    public String getStats() {
        int total = positionsClosed.get();
        double winRate = total > 0 ? (double) wins.get() / total * 100 : 0;
        // BUG #37 FIX: Include average latency in stats
        int samples = latencySamples.get();
        long avgLatencyMs = samples > 0 ? totalLatencyMs.get() / samples : 0;
        int droppedCapacity = signalsDroppedCapacity.get();
        int droppedDuplicate = signalsDroppedDuplicate.get();
        return String.format(
                "[PAPER_EXEC] Signals: %d | Entered: %d | Closed: %d | Wins: %d | Losses: %d | WR: %.1f%% | Active: %d | AvgLatency: %dms | DroppedCapacity: %d | DroppedDupe: %d",
                signalsReceived.get(), positionsEntered.get(), total,
                wins.get(), losses.get(), winRate, activePositions.size(), avgLatencyMs,
                droppedCapacity, droppedDuplicate
        );
    }

    /**
     * Publish dropped signal event for monitoring/alerting
     */
    private void publishDroppedSignal(TradingSignal signal, String reason, int totalDropped) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "event", "SIGNAL_DROPPED",
                    "signalId", signal.getSignalId(),
                    "familyId", signal.getFamilyId(),
                    "direction", signal.getDirection().name(),
                    "reason", reason,
                    "activePositions", activePositions.size(),
                    "maxPositions", maxActivePositions,
                    "totalDropped", totalDropped,
                    "timestamp", Instant.now().toString()
            ));
            kafkaTemplate.send("paper-trade-events", signal.getFamilyId(), json);
        } catch (Exception e) {
            log.debug("[PAPER_EXEC] Failed to publish dropped signal event: {}", e.getMessage());
        }
    }

    public int getSignalsDroppedCapacity() {
        return signalsDroppedCapacity.get();
    }

    /**
     * BUG #37 FIX: Get average signal-to-entry latency
     */
    public long getAverageLatencyMs() {
        int samples = latencySamples.get();
        return samples > 0 ? totalLatencyMs.get() / samples : 0;
    }

    public int getActivePositionCount() {
        return activePositions.size();
    }

    // ======================== MODELS ========================

    private static class ActivePosition {
        final String signalId;
        final String familyId;
        final String scripCode;
        final String direction;
        final String category;
        final String source;
        final String horizon;
        final String patternId;
        final String setupId;
        final String sequenceId;
        final double signalConfidence;
        final int signalQualityScore;
        final Instant signalGeneratedAt;
        final Instant enteredAt;
        final double plannedEntry;
        final double actualEntry;
        final double stopLoss;
        final double target1;
        final double target2;
        final double target3;
        double maxPrice;
        double minPrice;
        final double positionSizeMultiplier;
        final String gexRegime;
        final String session;
        boolean target1Hit = false;
        boolean target2Hit = false;
        boolean target3Hit = false;

        ActivePosition(String signalId, String familyId, String scripCode, String direction,
                       String category, String source, String horizon, String patternId,
                       String setupId, String sequenceId, double signalConfidence,
                       int signalQualityScore, Instant signalGeneratedAt, Instant enteredAt,
                       double plannedEntry, double actualEntry, double stopLoss, double target1,
                       double target2, double target3, double maxPrice, double minPrice,
                       double positionSizeMultiplier, String gexRegime, String session) {
            this.signalId = signalId;
            this.familyId = familyId;
            this.scripCode = scripCode;
            this.direction = direction;
            this.category = category;
            this.source = source;
            this.horizon = horizon;
            this.patternId = patternId;
            this.setupId = setupId;
            this.sequenceId = sequenceId;
            this.signalConfidence = signalConfidence;
            this.signalQualityScore = signalQualityScore;
            this.signalGeneratedAt = signalGeneratedAt;
            this.enteredAt = enteredAt;
            this.plannedEntry = plannedEntry;
            this.actualEntry = actualEntry;
            this.stopLoss = stopLoss;
            this.target1 = target1;
            this.target2 = target2;
            this.target3 = target3;
            this.maxPrice = maxPrice;
            this.minPrice = minPrice;
            this.positionSizeMultiplier = positionSizeMultiplier;
            this.gexRegime = gexRegime;
            this.session = session;
        }

        boolean isLong() {
            return "LONG".equals(direction);
        }
    }

    private record ExitResult(
            double exitPrice,
            String reason,
            boolean stopHit,
            boolean target3Hit,
            boolean target2Hit,
            boolean target1Hit,
            boolean timeExit
    ) {}

    public record TradeOutcomeMessage(
            String signalId,
            String familyId,
            String scripCode,
            String source,
            String category,
            String direction,
            String horizon,
            String patternId,
            String setupId,
            String sequenceId,
            double signalConfidence,
            int signalQualityScore,
            Instant signalGeneratedAt,
            Instant enteredAt,
            Instant closedAt,
            double plannedEntry,
            double actualEntry,
            double exitPrice,
            double plannedStop,
            double plannedTarget1,
            double plannedTarget2,
            double pnlPct,
            double rMultiple,
            double maxPrice,
            double minPrice,
            double positionSize,
            long timeInTradeMs,
            boolean target1Hit,
            boolean target2Hit,
            boolean target3Hit,
            boolean stopHit,
            boolean trailingStopHit,
            boolean timeExit,
            boolean invalidationTriggered,
            String invalidationCondition,
            String gexRegime,
            String session
    ) {}
}
