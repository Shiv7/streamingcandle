package com.kotsin.consumer.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Ensures all required Kafka topics are created on application startup.
 *
 * Topics for the new v2 architecture:
 * - tick-candles-1m: Tick-based OHLCV candles
 * - orderbook-metrics-1m: Orderbook derived metrics
 * - oi-metrics-1m: Open Interest metrics
 * - strategy-state: Strategy computation state
 */
@Configuration
public class TopicInitializer {

    private static final Logger log = LoggerFactory.getLogger(TopicInitializer.class);

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${kafka.topic.partitions:20}")
    private int partitions;

    @Value("${kafka.topic.replication-factor:1}")
    private short replicationFactor;

    // ==================== INPUT TOPICS ====================

    @Bean
    public NewTopic tickDataTopic() {
        return TopicBuilder.name("tick-data")
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic orderbookSnapshotTopic() {
        return TopicBuilder.name("orderbook-snapshot")
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic oiDataTopic() {
        return TopicBuilder.name("oi-data")
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }

    // ==================== OUTPUT TOPICS ====================

    @Bean
    public NewTopic tickCandles1m() {
        return TopicBuilder.name("tick-candles-1m")
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic orderbookMetrics1m() {
        return TopicBuilder.name("orderbook-metrics-1m")
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic oiMetrics1m() {
        return TopicBuilder.name("oi-metrics-1m")
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic strategyStateTopic() {
        return TopicBuilder.name("strategy-state")
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }

    // ==================== SIGNAL TOPICS ====================

    @Bean
    public NewTopic vcpSignalsTopic() {
        return TopicBuilder.name("vcp-signals")
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic ipuSignalsTopic() {
        return TopicBuilder.name("ipu-signals")
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic pivotSignalsTopic() {
        return TopicBuilder.name("pivot-signals")
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }

    /**
     * Log startup info about topic creation
     */
    @Bean
    public KafkaAdmin.NewTopics allTopics() {
        log.info("TopicInitializer: Ensuring all required topics exist");
        log.info("Input topics: tick-data, orderbook-snapshot, oi-data");
        log.info("Output topics: tick-candles-1m, orderbook-metrics-1m, oi-metrics-1m");
        log.info("Strategy topics: strategy-state, vcp-signals, ipu-signals, pivot-signals");
        log.info("Topic configuration: {} partitions, replication-factor={}", partitions, replicationFactor);

        return new KafkaAdmin.NewTopics(
            tickDataTopic(),
            orderbookSnapshotTopic(),
            oiDataTopic(),
            tickCandles1m(),
            orderbookMetrics1m(),
            oiMetrics1m(),
            strategyStateTopic(),
            vcpSignalsTopic(),
            ipuSignalsTopic(),
            pivotSignalsTopic()
        );
    }
}
