package com.kotsin.consumer.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * KafkaConfig - Basic Kafka consumer configuration.
 *
 * Provides bootstrap servers and consumer configuration for the new
 * independent aggregator architecture.
 */
@Component
@Slf4j
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id-prefix:streamingcandle-}")
    private String consumerGroupIdPrefix;

    @Value("${spring.kafka.consumer.auto-offset-reset:latest}")
    private String autoOffsetReset;

    @Value("${spring.kafka.consumer.max-poll-records:500}")
    private int maxPollRecords;

    @Value("${spring.kafka.consumer.fetch-max-wait-ms:500}")
    private int fetchMaxWaitMs;

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    public String getConsumerGroupIdPrefix() {
        return consumerGroupIdPrefix;
    }

    public String getAutoOffsetReset() {
        return autoOffsetReset;
    }

    public int getMaxPollRecords() {
        return maxPollRecords;
    }

    public int getFetchMaxWaitMs() {
        return fetchMaxWaitMs;
    }

    /**
     * Build consumer group ID for a specific aggregator.
     */
    public String getConsumerGroupId(String aggregatorName) {
        return consumerGroupIdPrefix + aggregatorName;
    }
}
