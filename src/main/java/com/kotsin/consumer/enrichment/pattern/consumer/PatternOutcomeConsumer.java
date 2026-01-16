package com.kotsin.consumer.enrichment.pattern.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.enrichment.pattern.store.PatternOutcomeStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * PatternOutcomeConsumer - Receives trade outcomes from tradeExecutionModule
 *
 * Listens to 'pattern-outcomes' topic which contains trade results
 * for pattern-based signals. Updates HistoricalStats for pattern learning.
 *
 * Flow:
 * 1. streamingcandle → PatternSignal → 'trading-signals' → tradeExecutionModule
 * 2. tradeExecutionModule executes trade and tracks outcome
 * 3. tradeExecutionModule → PatternOutcome → 'pattern-outcomes' → streamingcandle
 * 4. streamingcandle updates HistoricalStats for ML learning
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PatternOutcomeConsumer {

    private final PatternOutcomeStore outcomeStore;
    private final ObjectMapper objectMapper;

    private final AtomicLong outcomesProcessed = new AtomicLong(0);
    private final AtomicLong outcomeErrors = new AtomicLong(0);

    /**
     * Consume pattern outcomes from Kafka
     */
    @KafkaListener(
            topics = "${kafka.topics.pattern-outcomes:pattern-outcomes}",
            groupId = "${kafka.consumer.pattern-outcome-group:pattern-outcome-consumer}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeOutcome(String message) {
        try {
            JsonNode json = objectMapper.readTree(message);

            // Extract outcome data
            PatternOutcomeStore.PatternOutcome outcome = parseOutcome(json);

            if (outcome == null) {
                log.warn("[OUTCOME_CONSUMER] Could not parse outcome from message");
                outcomeErrors.incrementAndGet();
                return;
            }

            // Record the outcome
            outcomeStore.recordOutcome(outcome);
            outcomesProcessed.incrementAndGet();

            log.info("[OUTCOME_CONSUMER] Processed outcome for pattern {}: {} P&L={}%",
                    outcome.patternId(),
                    outcome.success() ? "WIN" : "LOSS",
                    String.format("%.2f", outcome.pnlPct()));

        } catch (Exception e) {
            log.error("[OUTCOME_CONSUMER] Error processing outcome: {}", e.getMessage());
            outcomeErrors.incrementAndGet();
        }
    }

    /**
     * Parse outcome from JSON
     */
    private PatternOutcomeStore.PatternOutcome parseOutcome(JsonNode json) {
        // Check if this is a pattern-based trade
        if (!json.has("patternId") || json.get("patternId").isNull()) {
            // Not a pattern-based trade, skip
            return null;
        }

        String patternId = json.path("patternId").asText();
        String sequenceId = json.path("sequenceId").asText(null);
        String signalId = json.path("signalId").asText(null);
        String familyId = json.path("familyId").asText(null);

        // Determine success from outcome
        boolean success = false;
        String outcome = json.path("outcome").asText("");
        if (outcome.equals("WIN") || outcome.equals("TARGET_HIT")) {
            success = true;
        } else if (json.has("success")) {
            success = json.path("success").asBoolean(false);
        }

        // Get P&L
        double pnlPct = json.path("pnlPct").asDouble(0);
        if (pnlPct == 0 && json.has("pnl")) {
            pnlPct = json.path("pnl").asDouble(0);
        }

        // Get timing
        long timeToOutcomeMs = json.path("timeToOutcomeMs").asLong(0);
        if (timeToOutcomeMs == 0 && json.has("holdTimeMs")) {
            timeToOutcomeMs = json.path("holdTimeMs").asLong(0);
        }

        // Target hits
        boolean target1Hit = json.path("target1Hit").asBoolean(false);
        boolean target2Hit = json.path("target2Hit").asBoolean(false);
        boolean stopHit = json.path("stopHit").asBoolean(false);

        return new PatternOutcomeStore.PatternOutcome(
                patternId,
                sequenceId,
                signalId,
                familyId,
                success,
                pnlPct,
                timeToOutcomeMs,
                target1Hit,
                target2Hit,
                stopHit
        );
    }

    /**
     * Get consumer statistics
     */
    public ConsumerStats getStats() {
        return new ConsumerStats(
                outcomesProcessed.get(),
                outcomeErrors.get()
        );
    }

    public record ConsumerStats(
            long processed,
            long errors
    ) {
        @Override
        public String toString() {
            return String.format("OutcomeConsumer: %d processed, %d errors",
                    processed, errors);
        }
    }
}
