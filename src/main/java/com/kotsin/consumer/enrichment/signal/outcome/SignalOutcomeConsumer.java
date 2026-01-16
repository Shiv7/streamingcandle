package com.kotsin.consumer.enrichment.signal.outcome;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.enrichment.pattern.store.PatternOutcomeStore;
import com.kotsin.consumer.enrichment.signal.SignalGenerator;
import com.kotsin.consumer.stats.service.SignalStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SignalOutcomeConsumer - Unified consumer for trade outcomes from Kafka
 *
 * Listens to:
 * - trade-outcomes: Trade execution results from trade execution module
 * - paper-trade-outcomes: Paper trading results for backtesting
 * - signal-expirations: Expired signal events
 *
 * Updates:
 * - SignalOutcomeStore: Records signal outcomes (MongoDB)
 * - PatternOutcomeStore: Updates pattern statistics
 * - SignalStatsService: Updates legacy stats for backward compatibility
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignalOutcomeConsumer {

    private final SignalOutcomeStore signalOutcomeStore;
    private final PatternOutcomeStore patternOutcomeStore;
    private final SignalGenerator signalGenerator;
    private final ObjectMapper objectMapper;

    // Optional backward compatibility with old stats service
    @Autowired(required = false)
    private SignalStatsService signalStatsService;

    /**
     * Statistics
     */
    private final AtomicLong outcomesProcessed = new AtomicLong(0);
    private final AtomicLong errorsCount = new AtomicLong(0);
    private final AtomicLong winsCount = new AtomicLong(0);
    private final AtomicLong lossesCount = new AtomicLong(0);

    // ======================== KAFKA LISTENERS ========================

    /**
     * Consume live trade outcomes
     */
    @KafkaListener(
            topics = "${kafka.topic.trade-outcomes:trade-outcomes}",
            groupId = "${kafka.consumer.group.outcomes:signal-outcome-consumer-v2}",
            containerFactory = "curatedKafkaListenerContainerFactory"
    )
    public void consumeTradeOutcome(ConsumerRecord<String, String> record) {
        try {
            TradeOutcomeMessage message = objectMapper.readValue(record.value(), TradeOutcomeMessage.class);
            processTradeOutcome(message, false);
        } catch (Exception e) {
            log.error("[OUTCOME_CONSUMER] Failed to process trade outcome: {}", e.getMessage());
            errorsCount.incrementAndGet();
        }
    }

    /**
     * Consume paper trade outcomes
     */
    @KafkaListener(
            topics = "${kafka.topic.paper-trade-outcomes:paper-trade-outcomes}",
            groupId = "${kafka.consumer.group.outcomes:signal-outcome-consumer-v2}",
            containerFactory = "curatedKafkaListenerContainerFactory"
    )
    public void consumePaperTradeOutcome(ConsumerRecord<String, String> record) {
        try {
            TradeOutcomeMessage message = objectMapper.readValue(record.value(), TradeOutcomeMessage.class);
            processTradeOutcome(message, true);
        } catch (Exception e) {
            log.error("[OUTCOME_CONSUMER] Failed to process paper trade outcome: {}", e.getMessage());
            errorsCount.incrementAndGet();
        }
    }

    /**
     * Consume signal expiration events
     */
    @KafkaListener(
            topics = "${kafka.topic.signal-expirations:signal-expirations}",
            groupId = "${kafka.consumer.group.outcomes:signal-outcome-consumer-v2}",
            containerFactory = "curatedKafkaListenerContainerFactory"
    )
    public void consumeSignalExpiration(ConsumerRecord<String, String> record) {
        try {
            SignalExpirationMessage message = objectMapper.readValue(record.value(), SignalExpirationMessage.class);
            processSignalExpiration(message);
        } catch (Exception e) {
            log.error("[OUTCOME_CONSUMER] Failed to process signal expiration: {}", e.getMessage());
            errorsCount.incrementAndGet();
        }
    }

    // ======================== PROCESSING ========================

    /**
     * Process a trade outcome message
     */
    private void processTradeOutcome(TradeOutcomeMessage message, boolean isPaperTrade) {
        log.debug("[OUTCOME_CONSUMER] Processing {} outcome for signal {}",
                isPaperTrade ? "paper" : "live", message.signalId);

        // Build signal outcome
        SignalOutcome outcome = buildSignalOutcome(message);
        outcome.setMetadata(java.util.Map.of("isPaperTrade", isPaperTrade));

        // Record signal outcome
        signalOutcomeStore.recordOutcome(outcome);

        // If this signal was from a pattern, update pattern stats
        if (message.patternId != null && !message.patternId.isEmpty()) {
            PatternOutcomeStore.PatternOutcome patternOutcome = new PatternOutcomeStore.PatternOutcome(
                    message.patternId,
                    message.sequenceId,
                    message.signalId,
                    message.familyId,
                    message.pnlPct > 0,
                    message.pnlPct,
                    message.timeInTradeMs,
                    message.target1Hit,
                    message.target2Hit,
                    message.stopHit
            );
            patternOutcomeStore.recordOutcome(patternOutcome);
        }

        // Update statistics
        outcomesProcessed.incrementAndGet();
        if (outcome.isProfitable()) {
            winsCount.incrementAndGet();
        } else if (outcome.getOutcome() == SignalOutcome.Outcome.LOSS) {
            lossesCount.incrementAndGet();
        }

        // Backward compatibility: Update old stats service if available
        if (signalStatsService != null) {
            try {
                signalStatsService.recordOutcome(
                        message.scripCode,
                        message.category != null ? message.category : "UNKNOWN",
                        outcome.isProfitable(),
                        message.rMultiple
                );
            } catch (Exception e) {
                log.debug("[OUTCOME_CONSUMER] Failed to update legacy stats: {}", e.getMessage());
            }
        }

        log.info("[OUTCOME_CONSUMER] Recorded {} outcome for {} {} {}: {:.2f}% P&L",
                isPaperTrade ? "paper" : "live",
                message.direction, message.category, message.familyId,
                message.pnlPct);
    }

    /**
     * Process signal expiration
     */
    private void processSignalExpiration(SignalExpirationMessage message) {
        log.debug("[OUTCOME_CONSUMER] Processing expiration for signal {}", message.signalId);

        SignalOutcome outcome = SignalOutcome.builder()
                .signalId(message.signalId)
                .familyId(message.familyId)
                .patternId(message.patternId)
                .setupId(message.setupId)
                .outcome(SignalOutcome.Outcome.EXPIRED)
                .exitReason(SignalOutcome.ExitReason.SIGNAL_EXPIRED)
                .closedAt(Instant.now())
                .signalGeneratedAt(message.generatedAt)
                .build();

        signalOutcomeStore.recordOutcome(outcome);

        // Update pattern stats if applicable
        if (message.patternId != null && !message.patternId.isEmpty()) {
            patternOutcomeStore.recordExpired(message.patternId, message.familyId);
        }

        outcomesProcessed.incrementAndGet();
    }

    /**
     * Build SignalOutcome from message
     */
    private SignalOutcome buildSignalOutcome(TradeOutcomeMessage msg) {
        SignalOutcome.Outcome outcome;
        SignalOutcome.ExitReason exitReason;

        if (msg.stopHit) {
            outcome = SignalOutcome.Outcome.LOSS;
            exitReason = SignalOutcome.ExitReason.STOP_LOSS_HIT;
        } else if (msg.target2Hit) {
            outcome = SignalOutcome.Outcome.WIN;
            exitReason = SignalOutcome.ExitReason.TARGET_2_HIT;
        } else if (msg.target1Hit) {
            outcome = SignalOutcome.Outcome.WIN;
            exitReason = SignalOutcome.ExitReason.TARGET_1_HIT;
        } else if (msg.trailingStopHit) {
            outcome = msg.pnlPct > 0 ? SignalOutcome.Outcome.WIN : SignalOutcome.Outcome.LOSS;
            exitReason = SignalOutcome.ExitReason.TRAILING_STOP_HIT;
        } else if (msg.timeExit) {
            outcome = msg.pnlPct > 0 ? SignalOutcome.Outcome.WIN :
                    (msg.pnlPct < -0.05 ? SignalOutcome.Outcome.LOSS : SignalOutcome.Outcome.BREAKEVEN);
            exitReason = SignalOutcome.ExitReason.TIME_EXIT;
        } else if (msg.invalidationTriggered) {
            outcome = SignalOutcome.Outcome.INVALIDATED;
            exitReason = SignalOutcome.ExitReason.INVALIDATION;
        } else {
            outcome = msg.pnlPct > 0 ? SignalOutcome.Outcome.WIN :
                    (msg.pnlPct < -0.05 ? SignalOutcome.Outcome.LOSS : SignalOutcome.Outcome.BREAKEVEN);
            exitReason = SignalOutcome.ExitReason.MANUAL_EXIT;
        }

        return SignalOutcome.builder()
                .signalId(msg.signalId)
                .familyId(msg.familyId)
                .scripCode(msg.scripCode)
                .source(msg.source)
                .category(msg.category)
                .direction(msg.direction)
                .horizon(msg.horizon)
                .patternId(msg.patternId)
                .setupId(msg.setupId)
                .sequenceId(msg.sequenceId)
                .signalConfidence(msg.signalConfidence)
                .signalQualityScore(msg.signalQualityScore)
                .signalGeneratedAt(msg.signalGeneratedAt)
                .enteredAt(msg.enteredAt)
                .closedAt(msg.closedAt)
                .plannedEntryPrice(msg.plannedEntry)
                .actualEntryPrice(msg.actualEntry)
                .exitPrice(msg.exitPrice)
                .plannedStopLoss(msg.plannedStop)
                .plannedTarget1(msg.plannedTarget1)
                .plannedTarget2(msg.plannedTarget2)
                .outcome(outcome)
                .exitReason(exitReason)
                .pnlPct(msg.pnlPct)
                .rMultiple(msg.rMultiple)
                .maxFavorableExcursion(msg.maxPrice)
                .maxAdverseExcursion(msg.minPrice)
                .positionSize(msg.positionSize)
                .target1Hit(msg.target1Hit)
                .target2Hit(msg.target2Hit)
                .target3Hit(msg.target3Hit)
                .stopLossHit(msg.stopHit)
                .trailingStopTriggered(msg.trailingStopHit)
                .timeInTradeMs(msg.timeInTradeMs)
                .gexRegimeAtSignal(msg.gexRegime)
                .sessionAtSignal(msg.session)
                .invalidationTriggered(msg.invalidationCondition)
                .version("2.0")
                .createdAt(Instant.now())
                .build();
    }

    // ======================== STATISTICS ========================

    /**
     * Get consumer statistics
     */
    public ConsumerStats getStats() {
        long processed = outcomesProcessed.get();
        long wins = winsCount.get();
        long losses = lossesCount.get();

        return new ConsumerStats(
                processed,
                wins,
                losses,
                errorsCount.get(),
                wins + losses > 0 ? (double) wins / (wins + losses) : 0
        );
    }

    public record ConsumerStats(
            long outcomesProcessed,
            long wins,
            long losses,
            long errors,
            double winRate
    ) {
        @Override
        public String toString() {
            return String.format("OutcomeConsumer: %d processed, %d wins, %d losses (%.1f%%), %d errors",
                    outcomesProcessed, wins, losses, winRate * 100, errors);
        }
    }

    // ======================== MESSAGE MODELS ========================

    /**
     * Trade outcome message from trade execution module
     */
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

    /**
     * Signal expiration message
     */
    public record SignalExpirationMessage(
            String signalId,
            String familyId,
            String patternId,
            String setupId,
            Instant generatedAt,
            String reason
    ) {}
}
