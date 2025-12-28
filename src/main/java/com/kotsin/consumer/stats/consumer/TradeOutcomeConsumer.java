package com.kotsin.consumer.stats.consumer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.data.service.SignalHistoryService;
import com.kotsin.consumer.stats.model.TradeOutcome;
import com.kotsin.consumer.stats.service.SignalStatsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * TradeOutcomeConsumer - Receives trade outcomes from TradeExecutionModule
 * 
 * Updates:
 * 1. SignalStats - for online learning
 * 2. SignalHistory - links outcomes to signal records
 */
@Component
public class TradeOutcomeConsumer {

    private static final Logger log = LoggerFactory.getLogger(TradeOutcomeConsumer.class);

    @Autowired
    private SignalStatsService statsService;

    @Autowired
    private SignalHistoryService historyService;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .findAndRegisterModules();

    @KafkaListener(
            topics = "trade-outcomes",
            groupId = "${kafka.consumer.stats-group:signal-stats-updater-v2}",
            containerFactory = "curatedKafkaListenerContainerFactory"
    )
    public void onTradeOutcome(String payload) {
        try {
            TradeOutcome outcome = MAPPER.readValue(payload, TradeOutcome.class);
            if (outcome == null) {
                log.warn("Received null trade outcome");
                return;
            }

            log.info("OUTCOME RECEIVED | {} | signalId={} | win={} | R={:.2f} | exit={}",
                    outcome.getScripCode(),
                    outcome.getSignalId(),
                    outcome.isWin(),
                    outcome.getRMultiple(),
                    outcome.getExitReason());

            // 1. Update stats for learning
            statsService.recordOutcome(
                    outcome.getScripCode(),
                    outcome.getSignalType() != null ? outcome.getSignalType() : "BREAKOUT_RETEST",
                    outcome.isWin(),
                    outcome.getRMultiple()
            );

            // 2. Link outcome to signal history
            historyService.linkOutcome(outcome);

            log.debug("OUTCOME PROCESSED | {} | stats and history updated", outcome.getScripCode());

        } catch (Exception e) {
            log.error("Failed to process trade outcome: {}", e.getMessage(), e);
        }
    }
}

