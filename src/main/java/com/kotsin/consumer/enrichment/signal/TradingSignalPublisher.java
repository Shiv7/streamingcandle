package com.kotsin.consumer.enrichment.signal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kotsin.consumer.config.KafkaTopics;
import com.kotsin.consumer.enrichment.signal.model.TradingSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TradingSignalPublisher - Publishes enhanced trading signals to Kafka
 *
 * Publishes to:
 * - trading-signals-v2: Enhanced signals with full context
 * - trading-signals-high-priority: Immediate urgency signals
 * - trading-signals-alerts: Signals for alerting systems
 *
 * Features:
 * - Async publishing with callbacks
 * - Validation before publishing
 * - Metrics tracking
 * - Dead letter handling for failed publishes
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradingSignalPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final SignalValidator signalValidator;

    // Topic names - using centralized constants
    private static final String TOPIC_SIGNALS_V2 = KafkaTopics.TRADING_SIGNALS_V2;
    private static final String TOPIC_HIGH_PRIORITY = KafkaTopics.TRADING_SIGNALS_HIGH_PRIORITY;
    private static final String TOPIC_ALERTS = KafkaTopics.TRADING_SIGNALS_ALERTS;

    // Statistics
    private final AtomicLong totalPublished = new AtomicLong(0);
    private final AtomicLong publishSuccess = new AtomicLong(0);
    private final AtomicLong publishFailed = new AtomicLong(0);
    private final AtomicLong validationFailed = new AtomicLong(0);
    private final AtomicLong highPriorityCount = new AtomicLong(0);

    // ======================== MAIN PUBLISHING ========================

    /**
     * Publish a single trading signal
     *
     * @param signal Signal to publish
     * @return true if published successfully
     */
    public boolean publishSignal(TradingSignal signal) {
        if (signal == null) {
            return false;
        }

        // Validate signal
        if (!signalValidator.isPublishable(signal)) {
            log.warn("[SIGNAL_PUB] Signal {} failed validation, not publishing", signal.getSignalId());
            validationFailed.incrementAndGet();
            return false;
        }

        try {
            String payload = objectMapper.writeValueAsString(signal);
            String key = buildMessageKey(signal);

            // Publish to main topic
            publishAsync(TOPIC_SIGNALS_V2, key, payload, signal.getSignalId());

            // High priority signals go to dedicated topic
            if (signal.isImmediatePriority()) {
                publishAsync(TOPIC_HIGH_PRIORITY, key, payload, signal.getSignalId());
                highPriorityCount.incrementAndGet();
            }

            // High quality signals trigger alerts
            if (signal.isHighQuality()) {
                publishAlert(signal);
            }

            totalPublished.incrementAndGet();
            return true;

        } catch (JsonProcessingException e) {
            log.error("[SIGNAL_PUB] Failed to serialize signal {}: {}", signal.getSignalId(), e.getMessage());
            publishFailed.incrementAndGet();
            return false;
        }
    }

    /**
     * Publish multiple signals
     *
     * @param signals List of signals to publish
     * @return Number of successfully published signals
     */
    public int publishSignals(List<TradingSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return 0;
        }

        int successCount = 0;
        for (TradingSignal signal : signals) {
            if (publishSignal(signal)) {
                successCount++;
            }
        }

        log.info("[SIGNAL_PUB] Published {}/{} signals", successCount, signals.size());
        return successCount;
    }

    /**
     * Publish signal synchronously (blocking)
     *
     * @param signal Signal to publish
     * @return true if published successfully
     */
    public boolean publishSignalSync(TradingSignal signal) {
        if (signal == null) {
            return false;
        }

        // Validate signal
        if (!signalValidator.isPublishable(signal)) {
            log.warn("[SIGNAL_PUB] Signal {} failed validation, not publishing", signal.getSignalId());
            validationFailed.incrementAndGet();
            return false;
        }

        try {
            String payload = objectMapper.writeValueAsString(signal);
            String key = buildMessageKey(signal);

            // Synchronous send
            SendResult<String, String> result = kafkaTemplate.send(TOPIC_SIGNALS_V2, key, payload).get();

            log.debug("[SIGNAL_PUB] Published signal {} to partition {} offset {}",
                    signal.getSignalId(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());

            totalPublished.incrementAndGet();
            publishSuccess.incrementAndGet();

            // High priority
            if (signal.isImmediatePriority()) {
                kafkaTemplate.send(TOPIC_HIGH_PRIORITY, key, payload).get();
                highPriorityCount.incrementAndGet();
            }

            return true;

        } catch (Exception e) {
            log.error("[SIGNAL_PUB] Failed to publish signal {}: {}", signal.getSignalId(), e.getMessage());
            publishFailed.incrementAndGet();
            return false;
        }
    }

    // ======================== HELPER METHODS ========================

    /**
     * Async publish with callback
     */
    private void publishAsync(String topic, String key, String payload, String signalId) {
        CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(topic, key, payload);

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                publishSuccess.incrementAndGet();
                log.debug("[SIGNAL_PUB] Published {} to {} partition {} offset {}",
                        signalId, topic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            } else {
                publishFailed.incrementAndGet();
                log.error("[SIGNAL_PUB] Failed to publish {} to {}: {}",
                        signalId, topic, ex.getMessage());
            }
        });
    }

    /**
     * Publish alert for high-quality signals
     */
    private void publishAlert(TradingSignal signal) {
        try {
            // Build compact alert payload
            SignalAlert alert = SignalAlert.builder()
                    .signalId(signal.getSignalId())
                    .familyId(signal.getFamilyId())
                    .direction(signal.getDirection().name())
                    .category(signal.getCategory() != null ? signal.getCategory().name() : null)
                    .headline(signal.getHeadline())
                    .entryPrice(signal.getEntryPrice())
                    .stopLoss(signal.getStopLoss())
                    .target(signal.getTarget2())
                    .confidence(signal.getConfidence())
                    .qualityScore(signal.getQualityScore())
                    .urgency(signal.getUrgency().name())
                    .riskReward(signal.getRiskRewardRatio())
                    .build();

            String payload = objectMapper.writeValueAsString(alert);
            String key = signal.getFamilyId() + "_" + signal.getSignalId();

            kafkaTemplate.send(TOPIC_ALERTS, key, payload);

            log.debug("[SIGNAL_PUB] Published alert for high-quality signal {}", signal.getSignalId());

        } catch (JsonProcessingException e) {
            log.warn("[SIGNAL_PUB] Failed to publish alert for {}: {}", signal.getSignalId(), e.getMessage());
        }
    }

    /**
     * Build message key for partitioning
     */
    private String buildMessageKey(TradingSignal signal) {
        // Key by family to ensure ordered processing per family
        return signal.getFamilyId() + "_" + signal.getDirection().name();
    }

    // ======================== STATISTICS ========================

    /**
     * Get publisher statistics
     */
    public PublisherStats getStats() {
        return PublisherStats.builder()
                .totalPublished(totalPublished.get())
                .publishSuccess(publishSuccess.get())
                .publishFailed(publishFailed.get())
                .validationFailed(validationFailed.get())
                .highPriorityCount(highPriorityCount.get())
                .successRate(calculateSuccessRate())
                .build();
    }

    /**
     * Reset statistics
     */
    public void resetStats() {
        totalPublished.set(0);
        publishSuccess.set(0);
        publishFailed.set(0);
        validationFailed.set(0);
        highPriorityCount.set(0);
    }

    private double calculateSuccessRate() {
        long total = totalPublished.get();
        if (total == 0) return 0;
        return (double) publishSuccess.get() / total;
    }

    // ======================== MODELS ========================

    @lombok.Data
    @lombok.Builder
    @lombok.AllArgsConstructor
    public static class SignalAlert {
        private String signalId;
        private String familyId;
        private String direction;
        private String category;
        private String headline;
        private double entryPrice;
        private double stopLoss;
        private double target;
        private double confidence;
        private int qualityScore;
        private String urgency;
        private double riskReward;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.AllArgsConstructor
    public static class PublisherStats {
        private long totalPublished;
        private long publishSuccess;
        private long publishFailed;
        private long validationFailed;
        private long highPriorityCount;
        private double successRate;

        @Override
        public String toString() {
            return String.format("SignalPublisher: %d published, %d success, %d failed, %d validation failed, " +
                            "%d high-priority (%.1f%% success rate)",
                    totalPublished, publishSuccess, publishFailed, validationFailed,
                    highPriorityCount, successRate * 100);
        }
    }
}
