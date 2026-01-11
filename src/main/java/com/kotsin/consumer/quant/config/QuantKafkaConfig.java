package com.kotsin.consumer.quant.config;

import com.kotsin.consumer.domain.model.FamilyCandle;
import com.kotsin.consumer.quant.model.QuantScore;
import com.kotsin.consumer.quant.model.QuantTradingSignal;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * QuantKafkaConfig - Kafka configuration for QuantScore system.
 *
 * Provides:
 * - KafkaTemplate for QuantScore (dashboard topic)
 * - KafkaTemplate for QuantTradingSignal (execution topic)
 * - ConcurrentKafkaListenerContainerFactory for FamilyCandle consumption
 */
@Configuration
@Slf4j
public class QuantKafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:quant-score-processor}")
    private String defaultGroupId;

    // ========== Producer Configuration ==========

    /**
     * Common producer properties with large message support
     */
    private Map<String, Object> producerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        // Large message support
        props.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 52428800); // 50MB
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 104857600L); // 100MB buffer
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        // Reliability settings
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 100);

        // Performance tuning
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 65536); // 64KB
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);

        return props;
    }

    /**
     * ProducerFactory for QuantScore
     */
    @Bean
    public ProducerFactory<String, QuantScore> quantScoreProducerFactory() {
        Map<String, Object> props = producerProps();
        return new DefaultKafkaProducerFactory<>(props);
    }

    /**
     * KafkaTemplate for sending QuantScore to dashboard topic
     */
    @Bean
    public KafkaTemplate<String, QuantScore> quantScoreKafkaTemplate() {
        KafkaTemplate<String, QuantScore> template = new KafkaTemplate<>(quantScoreProducerFactory());
        log.info("Created KafkaTemplate for QuantScore");
        return template;
    }

    /**
     * ProducerFactory for QuantTradingSignal
     */
    @Bean
    public ProducerFactory<String, QuantTradingSignal> quantSignalProducerFactory() {
        Map<String, Object> props = producerProps();
        return new DefaultKafkaProducerFactory<>(props);
    }

    /**
     * KafkaTemplate for sending QuantTradingSignal to execution topic
     */
    @Bean
    public KafkaTemplate<String, QuantTradingSignal> quantSignalKafkaTemplate() {
        KafkaTemplate<String, QuantTradingSignal> template = new KafkaTemplate<>(quantSignalProducerFactory());
        log.info("Created KafkaTemplate for QuantTradingSignal");
        return template;
    }

    /**
     * ProducerFactory for String messages (intelligence, narratives, etc.)
     */
    @Bean
    public ProducerFactory<String, String> stringProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // Large message support for JSON payloads
        props.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 10485760); // 10MB
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432L); // 32MB buffer
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        // Reliability settings
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);

        return new DefaultKafkaProducerFactory<>(props);
    }

    /**
     * KafkaTemplate for sending String messages (JSON)
     * Used by EnrichmentPipeline for market-narrative, market-intelligence, active-setups topics
     */
    @Bean
    public KafkaTemplate<String, String> stringKafkaTemplate() {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(stringProducerFactory());
        log.info("Created KafkaTemplate for String messages (intelligence/narratives)");
        return template;
    }

    // ========== Consumer Configuration ==========

    /**
     * Common consumer properties with large message support
     */
    private Map<String, Object> consumerProps(String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);

        // Large message support
        props.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 52428800); // 50MB
        props.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, 104857600); // 100MB

        // Consumer behavior
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, 1000);

        // Session and heartbeat
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000);

        // Performance tuning
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);

        return props;
    }

    /**
     * ConsumerFactory for FamilyCandle
     */
    @Bean
    public ConsumerFactory<String, FamilyCandle> familyCandleConsumerFactory() {
        Map<String, Object> props = consumerProps("quant-score-processor-v1");

        JsonDeserializer<FamilyCandle> deserializer = new JsonDeserializer<>(FamilyCandle.class);
        deserializer.addTrustedPackages("com.kotsin.consumer.domain.model", "com.kotsin.consumer.model");
        deserializer.setRemoveTypeHeaders(false);
        deserializer.setUseTypeMapperForKey(true);

        return new DefaultKafkaConsumerFactory<>(
            props,
            new StringDeserializer(),
            deserializer
        );
    }

    /**
     * ConcurrentKafkaListenerContainerFactory for FamilyCandle
     * Used by QuantScoreProcessor to consume from family-candle-1m topic
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, FamilyCandle> familyCandleListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, FamilyCandle> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(familyCandleConsumerFactory());
        factory.setConcurrency(3); // Parallel processing
        factory.setBatchListener(false); // Process one at a time for scoring
        log.info("Created ConcurrentKafkaListenerContainerFactory for FamilyCandle");
        return factory;
    }
}
