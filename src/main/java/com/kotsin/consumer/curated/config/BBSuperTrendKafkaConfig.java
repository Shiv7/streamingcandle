package com.kotsin.consumer.curated.config;

import com.kotsin.consumer.curated.model.BBSuperTrendSignal;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka configuration for BBSuperTrendSignal producer
 */
@Configuration
@Slf4j
public class BBSuperTrendKafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    /**
     * Producer properties for BBSuperTrendSignal
     */
    private Map<String, Object> producerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        // Reliability settings
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 100);

        // Performance tuning
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384); // 16KB
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        return props;
    }

    /**
     * ProducerFactory for BBSuperTrendSignal
     */
    @Bean
    public ProducerFactory<String, BBSuperTrendSignal> bbSuperTrendProducerFactory() {
        return new DefaultKafkaProducerFactory<>(producerProps());
    }

    /**
     * KafkaTemplate for sending BBSuperTrendSignal to bb-supertrend-signals topic
     */
    @Bean
    public KafkaTemplate<String, BBSuperTrendSignal> bbSuperTrendProducer() {
        KafkaTemplate<String, BBSuperTrendSignal> template = new KafkaTemplate<>(bbSuperTrendProducerFactory());
        log.info("Created KafkaTemplate for BBSuperTrendSignal");
        return template;
    }
}
