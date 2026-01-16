package com.kotsin.consumer.enrichment.pattern.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.config.KafkaTopics;
import com.kotsin.consumer.enrichment.pattern.model.PatternSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PatternSignalPublisher - Publishes PatternSignals to Kafka
 *
 * Signals are sent to 'trading-signals-v2' topic for consumption
 * by tradeExecutionModule which handles:
 * - Trade execution (paper/live)
 * - Outcome tracking
 * - Publishing results back to 'trade-outcomes' topic
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PatternSignalPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    // Use centralized topic constant - all signals go to trading-signals-v2
    private final String tradingSignalsTopic = KafkaTopics.TRADING_SIGNALS_V2;

    /**
     * Statistics
     */
    private final AtomicLong signalsPublished = new AtomicLong(0);
    private final AtomicLong signalsFailed = new AtomicLong(0);

    /**
     * Publish a single pattern signal
     */
    public CompletableFuture<Boolean> publishSignal(PatternSignal signal) {
        if (signal == null || !signal.isActionable()) {
            log.debug("[SIGNAL_PUB] Signal not actionable, skipping publish");
            return CompletableFuture.completedFuture(false);
        }

        try {
            String payload = objectMapper.writeValueAsString(signal);
            String key = signal.getFamilyId() + ":" + signal.getPatternId();

            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(tradingSignalsTopic, key, payload);

            return future.handle((result, ex) -> {
                if (ex != null) {
                    log.error("[SIGNAL_PUB] Failed to publish signal {}: {}",
                            signal.getSignalId(), ex.getMessage());
                    signalsFailed.incrementAndGet();
                    return false;
                } else {
                    signalsPublished.incrementAndGet();
                    log.info("[SIGNAL_PUB] Published signal {} to {} (partition {})",
                            signal.getSignalId().substring(0, 8),
                            tradingSignalsTopic,
                            result.getRecordMetadata().partition());
                    return true;
                }
            });

        } catch (Exception e) {
            log.error("[SIGNAL_PUB] Error serializing signal: {}", e.getMessage());
            signalsFailed.incrementAndGet();
            return CompletableFuture.completedFuture(false);
        }
    }

    /**
     * Publish multiple signals
     */
    public void publishSignals(List<PatternSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return;
        }

        for (PatternSignal signal : signals) {
            publishSignal(signal);
        }
    }

    /**
     * Get publishing statistics
     */
    public PublisherStats getStats() {
        return new PublisherStats(
                signalsPublished.get(),
                signalsFailed.get()
        );
    }

    public record PublisherStats(
            long published,
            long failed
    ) {
        public double getSuccessRate() {
            long total = published + failed;
            return total > 0 ? (double) published / total : 1.0;
        }

        @Override
        public String toString() {
            return String.format("SignalPublisher: %d published, %d failed (%.1f%% success)",
                    published, failed, getSuccessRate() * 100);
        }
    }
}
